package dosegram.render.motion

import dosegram.render.CardRenderer
import dosegram.render.RasterFailure
import dosegram.render.Rasteriser

import java.util.concurrent.TimeUnit
import scala.util.control.NonFatal

/** An animation as the encoder wrote it: an MP4 file of silent H.264 video. */
final class Mp4Video(val bytes: IArray[Byte]):
  def byteCount: Int = bytes.length

  /** A mutable copy, for an API that takes an array. */
  def toArray: Array[Byte] = IArray.genericWrapArray(bytes).toArray

/** Frames to video (the animated mood blob, VISUALS section 4.6, supervisor decision of 2026-11-28): every frame is an
  * SVG document of profile 1, linted and rasterised through the same port as a still card, read back to pixels and
  * encoded by [[Mp4Encoder]] as it arrives. The whole drawing has one budget; a frame that would start after it ends
  * the drawing with `TimedOut`, so a slow host sends the still card instead of keeping its chat waiting. `nanoTime` is
  * the clock; tests move it.
  */
final class MotionRenderer(rasteriser: Rasteriser, budgetMillis: Long, nanoTime: () => Long = () => System.nanoTime()):
  require(budgetMillis > 0, "a positive budget")
  private val stills = CardRenderer(rasteriser)

  def render(frames: Vector[String], framesPerSecond: Int): Either[RasterFailure, Mp4Video] =
    val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis)
    try
      encoded(frames.toList, deadline, None, framesPerSecond).map: session =>
        Mp4Video(IArray.unsafeFromArray(session.finish()))
    catch case NonFatal(_) => Left(RasterFailure.EncodingFailed)

  // The encoder is made for the size of the first frame; a later frame of another size is refused by it.
  @annotation.tailrec
  private def encoded(
      rest: List[String],
      deadline: Long,
      session: Option[Mp4Encoder],
      framesPerSecond: Int
  ): Either[RasterFailure, Mp4Encoder] =
    rest match
      case Nil                             => session.toRight(RasterFailure.EncodingFailed)
      case _ if nanoTime() - deadline >= 0 => Left(RasterFailure.TimedOut)
      case svg :: later                    =>
        pixels(svg) match
          case Left(failure) => Left(failure)
          case Right(frame)  =>
            val encoder = session.getOrElse(Mp4Encoder(frame.width, frame.height, framesPerSecond, Mp4Encoder.Standard))
            encoder.add(frame)
            encoded(later, deadline, Some(encoder), framesPerSecond)

  private def pixels(svg: String): Either[RasterFailure, RgbFrame] =
    stills.document(svg).flatMap(png => PngPixels.decode(png.toArray).left.map(_ => RasterFailure.NotPng))
