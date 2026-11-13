package dosegram.render

import dosegram.render.card.CardOutput
import dosegram.render.card.TextEquivalent
import dosegram.render.svg.SvgEmitter
import dosegram.render.svg.SvgProfile

/** PNG bytes as the rasteriser returned them. */
final class PngImage(val bytes: IArray[Byte]):
  def byteCount: Int = bytes.length

  /** A mutable copy, for an API that takes an array. */
  def toArray: Array[Byte] = IArray.genericWrapArray(bytes).toArray

/** Why an image could not be produced. Every case is answered with the text equivalent (VA-FB-01); none carries SVG
  * text or rasteriser output, which could quote user text (VA-PV-09).
  */
enum RasterFailure:
  case OutsideProfile(violationCount: Int)
  case Unavailable
  case TimedOut
  case TooLarge
  case Crashed(exitCode: Int)
  case NotPng

/** Port: stage 5 of the pipeline (research brief 04 section 7.1). `zoom` is 1 for chat photos and 2 for the document
  * path.
  */
trait Rasteriser:
  def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage]

/** A finished chat image with everything a sender needs: the bytes, the text equivalent from the same view model, a
  * neutral file name and the spoiler flag.
  */
final case class RenderedImage(
    templateId: String,
    fileName: String,
    png: PngImage,
    text: TextEquivalent,
    hasSpoiler: Boolean
)

/** Scene to PNG: emit, lint against the profile, rasterise. A document outside the profile is never rasterised. */
final class CardRenderer(rasteriser: Rasteriser):
  private val PhotoZoom = 1

  def render(card: CardOutput): Either[RasterFailure, RenderedImage] =
    document(SvgEmitter.emit(card.scene))
      .map(png => RenderedImage(card.scene.templateId, card.fileName, png, card.text, card.hasSpoiler))

  /** Rasterises an SVG document at photo size, but only when it is inside the profile. */
  def document(svg: String): Either[RasterFailure, PngImage] =
    val violations = SvgProfile.violations(svg)
    if violations.nonEmpty then Left(RasterFailure.OutsideProfile(violations.size))
    else rasteriser.rasterise(svg, PhotoZoom)
