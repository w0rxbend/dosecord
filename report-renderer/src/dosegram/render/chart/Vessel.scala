package dosegram.render.chart

import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.tokens.Tokens

/** The hydration vessel (VISUALS D7, section 6.9): a vertical meter, not a ring. The fill rises from the bottom to a
  * straight level line and never leaves the vessel; a value above the goal is stated in text only.
  */
object Vessel:
  val OutlinePx: Double = 6
  private val FillGapPx = 8.0

  /** The area the fill occupies when the vessel is full. */
  def interior(box: Box): Box = box.inset(OutlinePx + FillGapPx)

  def of(box: Box, cornerRadius: Double, share: Double, tokens: Tokens): Node =
    val outline =
      Node.outlinedRect(box.inset(OutlinePx / 2), cornerRadius - OutlinePx / 2, Stroke.butt(tokens.ink2, OutlinePx))
    // The glass is the panel well, so the empty part reads as room in the vessel and not as the card behind it.
    val glass = Node.filledRect(box.inset(OutlinePx), cornerRadius - OutlinePx, tokens.panel)
    val clamped = StrictMath.max(0.0, StrictMath.min(1.0, share))
    val fill = Option.when(clamped > 0) {
      Node.filledPath(fillPath(interior(box), cornerRadius - OutlinePx - FillGapPx, clamped), tokens.tide)
    }
    Node.Mark(
      MarkMeta("water.level", Set(Channel.Colour, Channel.Position, Channel.Label)),
      (glass +: fill.toVector) :+ outline
    )

  // The part of a rounded rectangle below the level line. Inside a corner zone the level line is shorter and its
  // ends lie on the corner arcs.
  private def fillPath(inner: Box, radius: Double, share: Double): PathData =
    val level = inner.bottom - inner.height * share
    val bottomInset = arcInset(radius, inner.bottom - level)
    val topInset = arcInset(radius, level - inner.y)
    val start = Point(inner.x + StrictMath.max(bottomInset, topInset), level)
    val levelEnd = Point(inner.right - StrictMath.max(bottomInset, topInset), level)
    val afterTop =
      if topInset > 0 then
        PathData.from(start).lineTo(levelEnd).arcTo(radius, false, true, Point(inner.right, inner.y + radius))
      else PathData.from(start).lineTo(levelEnd)
    val afterRight =
      if bottomInset > 0 then afterTop.arcTo(radius, false, true, Point(inner.right - radius, inner.bottom))
      else
        afterTop
          .lineTo(Point(inner.right, inner.bottom - radius))
          .arcTo(radius, false, true, Point(inner.right - radius, inner.bottom))
    val afterBottom = afterRight.lineTo(Point(inner.x + radius, inner.bottom))
    val afterLeft =
      if bottomInset > 0 then afterBottom.arcTo(radius, false, true, start)
      else afterBottom.arcTo(radius, false, true, Point(inner.x, inner.bottom - radius))
    val closing =
      if topInset > 0 then afterLeft.lineTo(Point(inner.x, inner.y + radius)).arcTo(radius, false, true, start)
      else afterLeft
    closing.closed

  // How far a horizontal line `depth` pixels inside a rounded corner is shortened at each end; zero outside the
  // corner zone.
  private def arcInset(radius: Double, depth: Double): Double =
    if depth >= radius then 0.0
    else radius - StrictMath.sqrt(radius * radius - (radius - depth) * (radius - depth))
