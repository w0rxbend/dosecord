package dosegram.render.card

import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

/** IMG-14, `card.milestone.v1` (Later, opt-in, on demand only): a logging milestone as that many cells. The cells are
  * neutral ink, not a domain colour, so the card cannot read as a medication reward; there are no runs and nothing that
  * could end (VISUALS D9, section 7.3). Up to 100 the badge is the number of cells itself; above 100 it is a fixed 10
  * by 10 block with the number.
  */
object MilestoneCard:
  val TemplateId = "card.milestone.v1"
  private val MaximumCells = 100
  private val Columns = 10
  private val YearColumns = 6
  private val MonthsInYear = 12
  private val CornerShare = 0.28
  private val GridToNumberPx = 208.0
  private val NumberToLabelPx = 80.0

  /** Cell side and gap: large cells for up to five rows, small ones for up to ten. */
  final private case class Grid(cellCount: Int, columns: Int, side: Double, gap: Double):
    def rows: Int = (cellCount + columns - 1) / columns
    def width: Double = columns * side + (columns - 1) * gap
    def height: Double = rows * side + (rows - 1) * gap

  def render(milestone: MilestoneData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val grid = gridFor(milestone)
    val top = (frame.size.height - (grid.height + GridToNumberPx + NumberToLabelPx)) / 2
    val numberBaseline = top + grid.height + GridToNumberPx
    val centerX = frame.size.width / 2
    val numberLook = TextLook(TextStyle.Hero, frame.tokens.ink, Align.Middle, TextKind.Value)
    val labelLook = TextLook(TextStyle.Body, frame.tokens.ink2, Align.Middle, TextKind.Label)
    val nodes = Vector(
      cells(grid, Point(centerX - grid.width / 2, top), frame),
      frame.text(milestone.count.toString, Point(centerX, numberBaseline), numberLook, frame.width),
      frame.text(
        context.copy.milestoneLabel(milestone),
        Point(centerX, numberBaseline + NumberToLabelPx),
        labelLook,
        frame.width
      )
    )
    val text = context.copy.milestoneText(milestone)
    CardOutput(frame.scene(TemplateId, nodes), TextEquivalent.of(text, text, context), "dosegram-milestone.png", false)

  private def gridFor(milestone: MilestoneData): Grid =
    milestone.kind match
      case MilestoneKind.FirstYear => Grid(MonthsInYear, YearColumns, 88, 20)
      case _                       =>
        val cellCount = StrictMath.min(MaximumCells, milestone.count)
        if cellCount <= 5 * Columns then Grid(cellCount, Columns, 64, 16) else Grid(cellCount, Columns, 44, 12)

  private def cells(grid: Grid, origin: Point, frame: CardFrame): Node =
    val pitch = grid.side + grid.gap
    val squares = (0 until grid.cellCount).toVector.map { index =>
      val box =
        Box(origin.x + (index % grid.columns) * pitch, origin.y + (index / grid.columns) * pitch, grid.side, grid.side)
      Node.filledRect(box, grid.side * CornerShare, frame.tokens.ink)
    }
    Node.Mark(MarkMeta("milestone.cells", Set(Channel.Shape, Channel.Label)), squares)
