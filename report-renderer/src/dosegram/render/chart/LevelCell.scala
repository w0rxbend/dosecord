package dosegram.render.chart

import dosegram.render.color.Rgb
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Tokens

/** The cell of the week cards (VISUALS D1, section 3.2): a rounded square whose track is the `grid` token and which
  * fills from the bottom to the day's share of its own plan or goal, like a small vessel. A day without a record is a
  * dotted outline and an estimate is a hatched outline: neither is ever drawn as an empty fill (VA-PR-03).
  */
object LevelCell:
  val CornerShare: Double = 0.28
  private val DottedInsetPx = 2.0
  private val HatchInsetPx = 6.0

  /** A cell filled to `share` (clamped to 0 to 1) in `colour`; a share of zero is the bare track. */
  def filled(role: String, box: Box, share: Double, colour: Rgb, tokens: Tokens): Node =
    val radius = box.width * CornerShare
    val clamped = StrictMath.max(0.0, StrictMath.min(1.0, share))
    val track = Node.filledRect(box, radius, tokens.grid)
    val fill = Option.when(clamped > 0)(Node.filledPath(Vessel.levelPath(box, radius, clamped), colour))
    Node.Mark(MarkMeta(role, Set(Channel.Colour, Channel.Position, Channel.Label)), track +: fill.toVector)

  /** The dotted outline of a day without a record. */
  def noRecord(role: String, box: Box, tokens: Tokens): Node =
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      Vector(Node.outlinedRect(box.inset(DottedInsetPx), box.width * CornerShare, Stroke.dotted(tokens.ink3)))
    )

  /** The hatched outline of an estimate, which is a bound and not an amount. */
  def estimate(role: String, box: Box, tokens: Tokens): Node =
    val outline = Node.outlinedRect(box.inset(DottedInsetPx), box.width * CornerShare, Stroke.butt(tokens.ink3, 4))
    Node.Mark(
      MarkMeta(role, Set(Channel.Pattern)),
      outline +: Hatch.lines(box.inset(HatchInsetPx), Stroke.butt(tokens.ink3, 4))
    )
