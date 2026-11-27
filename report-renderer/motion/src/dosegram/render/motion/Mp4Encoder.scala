package dosegram.render.motion

import org.jcodec.codecs.h264.H264Encoder
import org.jcodec.codecs.h264.encode.RateControl
import org.jcodec.codecs.h264.io.model.SliceType
import org.jcodec.common.Codec
import org.jcodec.common.VideoCodecMeta
import org.jcodec.common.io.ByteBufferSeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Packet
import org.jcodec.common.model.Picture
import org.jcodec.common.model.Size as PictureSize
import org.jcodec.containers.mp4.muxer.MP4Muxer

import java.nio.ByteBuffer

/** Frames to a silent H.264 video in an MP4 file, the form Telegram's `sendAnimation` takes (research brief 04 section
  * 10), in the JVM: JCodec 0.2.5 (BSD, passes the licence gate), Baseline profile, one key frame and then predicted
  * frames, at fixed quantisers. Pixels are converted to BT.601 limited-range YUV 4:2:0, the range a decoder assumes
  * when the stream does not say. The side lengths must be multiples of 16, so no cropping is needed. A frame is encoded
  * as it is added and only its compressed bytes are kept, so a loop never holds all of its pixels at once. The file
  * holds the time it was written, so only the video stream, not the file, is the same for the same frames.
  */
final class Mp4Encoder(width: Int, height: Int, framesPerSecond: Int, quantisers: Mp4Encoder.Quantisers):
  import Mp4Encoder.*
  require(width > 0 && height > 0 && width % Macroblock == 0 && height % Macroblock == 0, "sides are multiples of 16")
  require(framesPerSecond > 0, "a positive frame rate")

  private val encoder = H264Encoder(FixedQuantiser(quantisers))
  encoder.setKeyInterval(KeyInterval)
  private val picture = Picture.create(width, height, ColorSpace.YUV420J)
  private val scratch = ByteBuffer.allocate(width * height * 3)
  private val encoded = Vector.newBuilder[(ByteBuffer, Boolean)]
  private var count = 0

  def add(frame: RgbFrame): Unit =
    require(frame.width == width && frame.height == height, "frames of one size")
    toYuv(frame, picture)
    scratch.clear()
    val out = encoder.encodeFrame(picture, scratch)
    encoded += copy(out.getData) -> out.isKeyFrame
    count += 1

  /** The MP4 file of the frames added so far. */
  def finish(): Array[Byte] =
    require(count > 0, "at least one frame")
    val packets = encoded.result()
    val capacity = packets.map(_._1.remaining).sum + MuxHeadroom
    val channel = ByteBufferSeekableByteChannel.writeToByteBuffer(ByteBuffer.allocate(capacity))
    val muxer = MP4Muxer.createMP4MuxerToChannel(channel)
    val meta = VideoCodecMeta.createSimpleVideoCodecMeta(PictureSize(width, height), ColorSpace.YUV420J)
    val track = muxer.addVideoTrack(Codec.H264, meta)
    packets.zipWithIndex.foreach:
      case ((data, isKey), index) =>
        val kind = if isKey then Packet.FrameType.KEY else Packet.FrameType.INTER
        track.addFrame(Packet.createPacket(data, index.toLong, framesPerSecond, 1L, index.toLong, kind, null))
    muxer.finish()
    val contents = channel.getContents
    val bytes = Array.ofDim[Byte](contents.remaining)
    contents.get(bytes)
    bytes

object Mp4Encoder:
  private val Macroblock = 16
  private val ChromaShift = 128
  private val MuxHeadroom = 64 * 1024
  // Two loops of 60 frames between key frames: one key frame per loop. The frame counter of the stream stays within
  // the 2^16 the H.264 sequence header allows.
  private val KeyInterval = 120

  /** The quantisers of the key frame and of the predicted frames. */
  final case class Quantisers(key: Int, predicted: Int)

  /** Measured on the blob loops (supervisor decision of 2026-11-28): a luma PSNR of 38 dB or more in every frame. A
    * higher quantiser saves little, because the moving gradient changes every pixel of the blob.
    */
  val Standard: Quantisers = Quantisers(key = 24, predicted = 28)

  def encode(frames: Vector[RgbFrame], framesPerSecond: Int, quantisers: Quantisers = Standard): Array[Byte] =
    require(frames.nonEmpty, "at least one frame")
    val session = Mp4Encoder(frames.head.width, frames.head.height, framesPerSecond, quantisers)
    frames.foreach(session.add)
    session.finish()

  private def copy(data: ByteBuffer): ByteBuffer =
    val owned = ByteBuffer.allocate(data.remaining)
    owned.put(data.duplicate())
    owned.flip()
    owned

  // BT.601 limited range in fixed point; JCodec keeps every sample as its value minus 128. Chroma is the mean of each
  // 2 by 2 block.
  private def toYuv(frame: RgbFrame, picture: Picture): Unit =
    val (width, height, rgb) = (frame.width, frame.height, frame.rgb)
    val (luma, cb, cr) = (picture.getPlaneData(0), picture.getPlaneData(1), picture.getPlaneData(2))
    def channel(pixel: Int, offset: Int): Int = rgb(pixel * 3 + offset) & 0xff
    for pixel <- 0 until width * height do
      val (r, g, b) = (channel(pixel, 0), channel(pixel, 1), channel(pixel, 2))
      luma(pixel) = ((((66 * r + 129 * g + 25 * b + 128) >> 8) + 16) - ChromaShift).toByte
    val chromaWidth = width / 2
    for
      y <- 0 until height / 2
      x <- 0 until chromaWidth
    do
      val (top, bottom) = ((2 * y) * width + 2 * x, (2 * y + 1) * width + 2 * x)
      def mean(offset: Int): Int =
        (channel(top, offset) + channel(top + 1, offset) + channel(bottom, offset) + channel(bottom + 1, offset)) / 4
      val (r, g, b) = (mean(0), mean(1), mean(2))
      cb(y * chromaWidth + x) = ((((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128) - ChromaShift).toByte
      cr(y * chromaWidth + x) = ((((112 * r - 94 * g - 18 * b + 128) >> 8) + 128) - ChromaShift).toByte

/** One quantiser per frame kind for the whole video, never changed by the bits a macroblock took: the frames are small
  * and few, so a steady quality matters more than a steady rate.
  */
final private class FixedQuantiser(quantisers: Mp4Encoder.Quantisers) extends RateControl:
  override def startPicture(size: PictureSize, maxSize: Int, sliceType: SliceType): Int =
    if sliceType == SliceType.P then quantisers.predicted else quantisers.key
  override def initialQpDelta(): Int = 0
  override def accept(bits: Int): Int = 0
