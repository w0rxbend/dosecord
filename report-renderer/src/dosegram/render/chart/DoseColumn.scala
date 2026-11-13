package dosegram.render.chart

import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Tokens

/** The doses of one day as a column of cell glyphs (VISUALS sections 6.5 and 6.8): up to four glyphs stacked in
  * occurrence order; above four, one column of the same height split in proportion to the status shares, so no dose is
  * silently dropped (VA-LA-03).
  */
object DoseColumn:
  val MaximumGlyphs: Int = 4
  val GapPx: Double = 8
  private val CornerPx = 12.0

  /** How many glyph rows a day with this many doses occupies. */
  def rowsFor(doseCount: Int): Int = StrictMath.min(MaximumGlyphs, doseCount)

  def of(statuses: Vector[DoseStatus], origin: Point, cellPx: Double, tokens: Tokens): Vector[Node] =
    if statuses.size <= MaximumGlyphs then
      statuses.zipWithIndex.map { (status, index) =>
        DoseGlyph.of(status, Box(origin.x, origin.y + index * (cellPx + GapPx), cellPx, cellPx), tokens)
      }
    else
      val height = MaximumGlyphs * cellPx + (MaximumGlyphs - 1) * GapPx
      Vector(proportional(statuses, Box(origin.x, origin.y, cellPx, height), tokens))

  /** One block split in proportion to the status shares: taken rises from the bottom in the medication hue, a rule
    * marks the top of the skipped share, and the rest stays empty inside the outline.
    */
  def proportional(statuses: Vector[DoseStatus], block: Box, tokens: Tokens): Node =
    val takenHeight = block.height * statuses.count(_.isTaken) / statuses.size
    val skippedHeight = block.height * statuses.count(_ == DoseStatus.Skipped) / statuses.size
    val parts = Vector(
      Option.when(takenHeight >= 2 * CornerPx) {
        Node.filledRect(Box(block.x, block.bottom - takenHeight, block.width, takenHeight), CornerPx, tokens.spruce)
      },
      Option.when(skippedHeight > 0 && takenHeight + skippedHeight < block.height) {
        val y = block.bottom - takenHeight - skippedHeight
        Node.Line(Point(block.x + 3, y), Point(block.right - 3, y), Stroke.butt(tokens.ink2, 6))
      },
      Some(DoseGlyph.hollow(block, CornerPx, tokens))
    ).flatten
    Node.Mark(MarkMeta("dose.column", Set(Channel.Colour, Channel.Position, Channel.Fill)), parts)
