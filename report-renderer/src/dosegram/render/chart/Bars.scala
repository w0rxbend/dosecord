package dosegram.render.chart

import dosegram.render.color.Rgb
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.PathData
import dosegram.render.scene.Point

/** Bars (VISUALS section 3.2): the data end has the cell radius, capped at half the bar's thickness; the baseline end
  * is square. Bars always start at zero.
  */
object Bars:
  private val CellRadiusPx = 12.0

  /** A bar that grows upward from the bottom edge of its box. */
  def upright(box: Box, color: Rgb): Node =
    val radius = StrictMath.min(CellRadiusPx, StrictMath.min(box.width / 2, box.height))
    val path = PathData
      .from(Point(box.x, box.bottom))
      .lineTo(Point(box.x, box.y + radius))
      .arcTo(radius, false, true, Point(box.x + radius, box.y))
      .lineTo(Point(box.right - radius, box.y))
      .arcTo(radius, false, true, Point(box.right, box.y + radius))
      .lineTo(Point(box.right, box.bottom))
      .closed
    Node.filledPath(path, color)

  /** A bar that grows toward the end edge from the start edge of its box. */
  def sideways(box: Box, color: Rgb): Node =
    val radius = StrictMath.min(CellRadiusPx, StrictMath.min(box.height / 2, box.width))
    val path = PathData
      .from(Point(box.x, box.y))
      .lineTo(Point(box.right - radius, box.y))
      .arcTo(radius, false, true, Point(box.right, box.y + radius))
      .lineTo(Point(box.right, box.bottom - radius))
      .arcTo(radius, false, true, Point(box.right - radius, box.bottom))
      .lineTo(Point(box.x, box.bottom))
      .closed
    Node.filledPath(path, color)
