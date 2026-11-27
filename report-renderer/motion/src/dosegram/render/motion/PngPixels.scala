package dosegram.render.motion

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** The opaque pixels of one frame, three bytes per pixel in red, green, blue order, row by row from the top. */
final class RgbFrame(val width: Int, val height: Int, val rgb: Array[Byte]):
  require(width > 0 && height > 0, "a frame has pixels")
  require(rgb.length == width * height * 3, "three bytes per pixel")

/** Reads the PNG the rasteriser writes (resvg and dosegram-raster: 8-bit RGBA, or RGB, without interlacing) into an
  * [[RgbFrame]], without AWT. Alpha is dropped: every frame paints its own opaque background first. Anything else (a
  * palette, 16 bits, interlacing, a broken checksum or stream) is refused, never guessed.
  */
object PngPixels:
  private val Signature = Array[Byte](-119, 'P', 'N', 'G', '\r', '\n', 26, '\n')
  private val Rgb = 2
  private val Rgba = 6
  private val MaxSide = 4096

  def decode(png: Array[Byte]): Either[String, RgbFrame] =
    if png.length < Signature.length + 25 || !png.take(Signature.length).sameElements(Signature) then Left("not a PNG")
    else
      chunks(ByteBuffer.wrap(png, Signature.length, png.length - Signature.length)).flatMap: found =>
        found.collectFirst { case ("IHDR", data) => data } match
          case None         => Left("no header")
          case Some(header) =>
            val idat = found.collect { case ("IDAT", data) => data }
            Header
              .of(header)
              .flatMap(head => inflate(idat, head.rowBytes * head.height + head.height).flatMap(head.pixels))

  final private case class Header(width: Int, height: Int, channels: Int):
    def rowBytes: Int = width * channels

    /** Undoes the per-row filters of the PNG specification (section 9) and keeps red, green and blue. */
    def pixels(raw: Array[Byte]): Either[String, RgbFrame] =
      val stride = rowBytes + 1
      if raw.length != stride * height then Left("short image data")
      else
        val current = Array.ofDim[Byte](rowBytes)
        val previous = Array.ofDim[Byte](rowBytes)
        val rgb = Array.ofDim[Byte](width * height * 3)
        val filtered = (0 until height).forall: row =>
          val filter = raw(row * stride)
          System.arraycopy(raw, row * stride + 1, current, 0, rowBytes)
          val known = unfilter(filter, current, previous)
          if known then
            (0 until width).foreach: column =>
              System.arraycopy(current, column * channels, rgb, (row * width + column) * 3, 3)
            System.arraycopy(current, 0, previous, 0, rowBytes)
          known
        if filtered then Right(RgbFrame(width, height, rgb)) else Left("unknown row filter")

    private def unfilter(filter: Byte, line: Array[Byte], above: Array[Byte]): Boolean =
      def left(index: Int): Int = if index >= channels then line(index - channels) & 0xff else 0
      def up(index: Int): Int = above(index) & 0xff
      def upLeft(index: Int): Int = if index >= channels then above(index - channels) & 0xff else 0
      def add(index: Int, predictor: Int): Unit = line(index) = ((line(index) & 0xff) + predictor).toByte
      filter match
        case 0 => true
        case 1 => line.indices.foreach(index => add(index, left(index))); true
        case 2 => line.indices.foreach(index => add(index, up(index))); true
        case 3 => line.indices.foreach(index => add(index, (left(index) + up(index)) / 2)); true
        case 4 => line.indices.foreach(index => add(index, paeth(left(index), up(index), upLeft(index)))); true
        case _ => false

    private def paeth(a: Int, b: Int, c: Int): Int =
      val p = a + b - c
      val (pa, pb, pc) = (math.abs(p - a), math.abs(p - b), math.abs(p - c))
      if pa <= pb && pa <= pc then a else if pb <= pc then b else c

  private object Header:
    def of(data: Array[Byte]): Either[String, Header] =
      val buffer = ByteBuffer.wrap(data)
      val (width, height) = (buffer.getInt(0), buffer.getInt(4))
      val (depth, colour, interlace) = (data(8), data(9), data(12))
      val channels = colour match
        case Rgb  => 3
        case Rgba => 4
        case _    => 0
      if data.length != 13 then Left("bad header")
      else if width <= 0 || height <= 0 || width > MaxSide || height > MaxSide then Left("bad size")
      else if depth != 8 || channels == 0 || interlace != 0 then Left("unsupported pixel format")
      else Right(Header(width, height, channels))

  // Every chunk up to IEND, each checked against its CRC.
  @annotation.tailrec
  private def chunks(
      buffer: ByteBuffer,
      found: Vector[(String, Array[Byte])] = Vector.empty
  ): Either[String, Vector[(String, Array[Byte])]] =
    if buffer.remaining < 12 then Left("truncated chunk")
    else
      val length = buffer.getInt
      if length < 0 || length > buffer.remaining - 8 then Left("truncated chunk")
      else
        val typeAndData = Array.ofDim[Byte](4 + length)
        buffer.get(typeAndData)
        val crc = CRC32()
        crc.update(typeAndData)
        if buffer.getInt != crc.getValue.toInt then Left("chunk checksum")
        else
          val kind = String(typeAndData, 0, 4, StandardCharsets.US_ASCII)
          val all = found :+ (kind -> typeAndData.drop(4))
          if kind == "IEND" then Right(all) else chunks(buffer, all)

  private def inflate(parts: Vector[Array[Byte]], expected: Int): Either[String, Array[Byte]] =
    val inflater = Inflater()
    try
      inflater.setInput(parts.foldLeft(Array.emptyByteArray)(_ ++ _))
      val out = Array.ofDim[Byte](expected)
      var filled = 0
      while filled < expected && !inflater.finished && !inflater.needsInput && !inflater.needsDictionary do
        filled += inflater.inflate(out, filled, expected - filled)
      if filled == expected && inflater.finished then Right(out) else Left("short image data")
    catch case _: DataFormatException => Left("broken image data")
    finally inflater.end()
