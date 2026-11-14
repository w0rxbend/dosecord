package dosegram.render.card

import dosegram.render.copy.EnglishImageCopy
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

import java.time.LocalDate

/** The values of IMG-15: the label, the count the user keeps, the unit word, the user's threshold (in units when it is
  * one, always as words), the last day the count covers on the user's schedule, and the text equivalent.
  */
final case class RefillData(
    label: String,
    onHand: BigDecimal,
    unit: String,
    thresholdUnits: Option[BigDecimal],
    threshold: String,
    coversUntil: Option[LocalDate],
    text: String
)

/** IMG-15, `card.refill.v1` (VISUALS section 6.16): a row of cells, one solid `ink` cell per unit left up to the width
  * of the row, the rest dotted; the user's units threshold as a marker in `attention`, the only template that uses it;
  * the count in large type; the date the count covers until; the source of the count. It never tells the user to
  * obtain, buy or take anything (VA-IMG-15a). In discreet mode the label is replaced by the generic line.
  */
object RefillCard:
  val TemplateId = "card.refill.v1"
  val Cells = 30
  private val CellGapPx = 6.0
  private val CellHeightPx = 56.0
  private val RowTop = 268.0
  private val NumberColumnPx = 240.0

  // The English words, for callers that name them; the card itself asks the context's catalogue (ROADMAP G2.22).
  val Title: String = EnglishImageCopy.refillTitle
  val DiscreetLabel: String = EnglishImageCopy.refillDiscreetLabel
  val Source: String = EnglishImageCopy.refillSource

  def render(data: RefillData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Wide, context.tokens.surface)
    val tokens = frame.tokens
    val copy = context.copy
    val discreet = context.privacy == Privacy.Discreet
    val label = if discreet then copy.refillDiscreetLabel else data.label
    val rowWidth = frame.width - NumberColumnPx
    val cell = (rowWidth - CellGapPx * (Cells - 1)) / Cells
    val solid = data.onHand.setScale(0, BigDecimal.RoundingMode.FLOOR).toInt.min(Cells).max(0)
    val cells = (0 until Cells).map: index =>
      val box = Box(frame.left + index * (cell + CellGapPx), RowTop, cell, CellHeightPx)
      if index < solid then
        Node.Mark(
          MarkMeta("refill.cell.left", Set(Channel.Fill, Channel.Label)),
          Vector(Node.Rect(box, 4, Some(Paint.Solid(tokens.ink)), None))
        )
      else
        Node.Mark(
          MarkMeta("refill.cell.used", Set(Channel.Pattern, Channel.Label)),
          Vector(Node.Rect(box, 4, None, Some(Stroke.dotted(tokens.ink3))))
        )
    val marker = data.thresholdUnits
      .filter(n => n > 0 && n <= Cells)
      .map: n =>
        val x = frame.left + n.toDouble * (cell + CellGapPx) - CellGapPx / 2
        Node.Mark(
          MarkMeta("refill.threshold", Set(Channel.Colour, Channel.Position, Channel.Label)),
          Vector(Node.Rect(Box(x - 3, RowTop - 16, 6, CellHeightPx + 32), 3, Some(Paint.Solid(tokens.attention)), None))
        )
    val number = data.onHand.bigDecimal.stripTrailingZeros.toPlainString
    val ink = TextLook(TextStyle.Body, tokens.ink, Align.Start, TextKind.Label)
    val quiet = TextLook(TextStyle.Label, tokens.ink2, Align.Start, TextKind.Label)
    val right = TextLook(TextStyle.Hero, tokens.ink, Align.End, TextKind.Value)
    val unitLook = TextLook(TextStyle.Label, tokens.ink2, Align.End, TextKind.Label)
    val covers = data.coversUntil.map(copy.refillCovers)
    val nodes = frame.header(copy.refillTitle, "")
      ++ Vector(
        frame.text(
          label,
          Point(frame.left, 212),
          TextLook(TextStyle.BodyStrong, tokens.ink, Align.Start, TextKind.Label),
          rowWidth
        )
      )
      ++ cells
      ++ marker.toVector
      ++ Vector(
        frame.text(copy.refillThreshold(data.threshold), Point(frame.left, 392), quiet, rowWidth),
        frame.text(number, Point(frame.right, 360), right, NumberColumnPx),
        frame.text(copy.refillUnitsLeft(data.unit), Point(frame.right, 420), unitLook, NumberColumnPx)
      )
      ++ covers.map(line => frame.text(line, Point(frame.left, 480), ink, frame.width)).toVector
      ++ Vector(frame.text(copy.refillSource, Point(frame.left, 540), quiet, frame.width), frame.footer)
    val text = TextEquivalent.of(data.text, copy.refillDiscreetLabel, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-refill.png", discreet)
end RefillCard
