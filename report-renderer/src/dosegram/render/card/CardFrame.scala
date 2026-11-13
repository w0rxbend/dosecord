package dosegram.render.card

import dosegram.render.color.Rgb
import dosegram.render.copy.ImageCopy
import dosegram.render.scene.Align
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Size
import dosegram.render.scene.TextKind
import dosegram.render.text.TextFitter
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Theme
import dosegram.render.tokens.Tokens

/** The privacy level a card is rendered at (VISUALS section 5.4). `Named` is L2, `Aggregate` is L1, and `Discreet` is
  * L1 with neutral row labels, a generic caption and the spoiler (VA-PV-03).
  */
enum Privacy:
  case Named, Aggregate, Discreet

  def showsNames: Boolean = this == Named

/** Everything a template needs besides its view model. */
final case class CardContext(theme: Theme, privacy: Privacy, copy: ImageCopy, fitter: TextFitter):
  def tokens: Tokens = Tokens.of(theme)

/** The text equivalent of an image (VA-TX-01, VA-TX-05): the caption sent with the photo, the full text view, and the
  * alt text stored with the asset.
  */
final case class TextEquivalent(caption: String, full: String):
  def altText: String = full.take(TextEquivalent.CaptionLimit)

object TextEquivalent:
  val CaptionLimit: Int = 1024

  /** The full text is the caption when it fits; otherwise the caption is the summary and "Details below.". In discreet
    * mode the caption is always the generic line.
    */
  def of(full: String, generic: String, context: CardContext): TextEquivalent =
    val caption =
      if context.privacy == Privacy.Discreet then generic
      else if full.length <= CaptionLimit then full
      else s"$generic ${context.copy.detailsBelow}"
    TextEquivalent(caption, full)

/** One rendered card before rasterisation: the scene, its text equivalent, a neutral constant file name (VA-PV-05) and
  * whether it is sent behind a spoiler.
  */
final case class CardOutput(scene: Scene, text: TextEquivalent, fileName: String, hasSpoiler: Boolean)

/** How one line of text is drawn. */
final case class TextLook(style: TextStyle, ink: Rgb, align: Align, kind: TextKind)

/** The shared card structure of VISUALS section 3.5: 64 px outer margin, a header with the title and the period, and
  * the non-advice footer of every personal card. Every string goes through the fitter, so no text overflows.
  */
final class CardFrame(context: CardContext, val size: Size, val background: Rgb):
  val tokens: Tokens = context.tokens
  val left: Double = CardFrame.MarginPx
  val right: Double = size.width - CardFrame.MarginPx
  val width: Double = right - left
  val footerBaseline: Double = size.height - CardFrame.MarginPx

  /** The lowest point of the body: one line of room above the footer. */
  val bodyBottom: Double = footerBaseline - CardFrame.FooterClearancePx

  /** How far a body that would span `top` to `bottom` moves down so that the room the card has left is shared above
    * and below it. A body that already reaches the footer stays where it is.
    */
  def balance(top: Double, bottom: Double): Double =
    val below = bodyBottom - bottom
    val above = top - CardFrame.BodyTop
    val shift = StrictMath.max(0.0, StrictMath.min(below, (below - above) / 2))
    StrictMath.floor(shift / CardFrame.GridPx) * CardFrame.GridPx

  def look(style: TextStyle, ink: Rgb): TextLook = TextLook(style, ink, Align.Start, TextKind.Label)

  /** One fitted line of text. */
  def text(raw: String, anchor: Point, look: TextLook, maxWidth: Double): Node =
    val fitted = context.fitter.line(raw, look.style, maxWidth)
    Node.Text(fitted.lines.head, anchor, look.align, fitted.style, look.ink, look.kind)

  /** The title at the start edge and the period at the end edge, on one baseline. */
  def header(title: String, period: String): Vector[Node] =
    val periodLook = TextLook(TextStyle.BodyMedium, tokens.ink2, Align.End, TextKind.Value)
    val periodWidth = context.fitter.width(period, TextStyle.BodyMedium)
    Vector(
      text(title, Point(left, CardFrame.HeaderBaseline), look(TextStyle.Title, tokens.ink), width - periodWidth - 32),
      text(period, Point(right, CardFrame.HeaderBaseline), periodLook, width)
    )

  def footer: Node = text(context.copy.footer, Point(left, footerBaseline), look(TextStyle.Label, tokens.ink3), width)

  def scene(templateId: String, nodes: Vector[Node]): Scene = Scene(templateId, size, background, nodes)

object CardFrame:
  val MarginPx: Double = 64
  val HeaderBaseline: Double = 120

  /** Where the body may start under the header. */
  val BodyTop: Double = 160
  private val FooterClearancePx = 64.0

  /** Positions are multiples of 4 px (VISUALS section 3.5). */
  private val GridPx = 4.0
  val Square: Size = Size(1080, 1080)
  val Portrait: Size = Size(1080, 1350)
