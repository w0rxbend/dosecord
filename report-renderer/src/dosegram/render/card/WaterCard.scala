package dosegram.render.card

import dosegram.render.chart.Vessel
import dosegram.render.scene.Align
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

/** IMG-08, `card.water.v1`: progress toward today's water goal as one vessel and one number. The card never suggests an
  * amount and always calls the goal the user's own (VA-IMG-08a). Nothing overflows or celebrates above the goal.
  */
object WaterCard:
  val TemplateId = "card.water.v1"
  private val VesselBox = Box(64, 184, 360, 640)
  private val VesselRadius = 96.0
  private val TickFrom = 436.0
  private val TickTo = 456.0
  private val TickLabelX = 468.0
  // The widest hero, "100%", needs 457 px; the column starts clear of the tick labels beside it.
  private val FiguresLeft = 540.0
  private val HeroBaseline = 472.0
  private val FractionBaseline = 568.0
  private val FirstNoteBaseline = 652.0
  private val NoteLinePx = 56.0
  private val GoalNoteBaseline = 916.0

  def render(water: WaterData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val nodes = frame.header(copy.waterTitle, copy.dayDate(water.date))
      ++ Vector(Vessel.of(VesselBox, VesselRadius, water.progress.share, frame.tokens))
      ++ ticks(water.progress.goalMl, frame, context)
      ++ figures(water, frame, context)
      ++ Vector(
        frame.text(
          copy.goalIsYours,
          Point(frame.left, GoalNoteBaseline),
          frame.look(TextStyle.Label, frame.tokens.ink2),
          frame.width
        ),
        frame.footer
      )
    val text = TextEquivalent.of(copy.waterText(water), copy.waterDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-water.png", context.privacy == Privacy.Discreet)

  // A tick and a label at each quarter of the goal; only the top label carries the unit.
  private def ticks(goalMl: Int, frame: CardFrame, context: CardContext): Vector[Node] =
    val interior = Vessel.interior(VesselBox)
    val look = TextLook(TextStyle.Label, frame.tokens.ink3, Align.Start, TextKind.Axis)
    (1 to 4).toVector.flatMap { quarter =>
      val y = interior.bottom - interior.height * quarter / 4
      val volume = goalMl * quarter / 4
      val label = if quarter == 4 then context.copy.waterTopTick(volume) else context.copy.waterTick(volume)
      Vector(
        Node.Line(Point(TickFrom, y), Point(TickTo, y), Stroke.solid(frame.tokens.ink3, 4)),
        frame.text(label, Point(TickLabelX, y + 14), look, 120)
      )
    }

  // The end-aligned column: the percentage of the user's goal, the fraction, and the entry notes.
  private def figures(water: WaterData, frame: CardFrame, context: CardContext): Vector[Node] =
    val copy = context.copy
    val columnWidth = frame.right - FiguresLeft
    def line(content: String, baseline: Double, style: TextStyle, kind: TextKind): Node =
      val ink = if style == TextStyle.Body then frame.tokens.ink2 else frame.tokens.ink
      frame.text(content, Point(frame.right, baseline), TextLook(style, ink, Align.End, kind), columnWidth)
    val notes =
      if water.entryCount == 0 then Vector(copy.nothingLoggedYet)
      else copy.entries(water.entryCount) +: water.lastEntry.map(copy.lastEntryAt).toVector
    Vector(
      line(copy.percent(water.progress.percent), HeroBaseline, TextStyle.Hero, TextKind.Value),
      line(copy.waterFraction(water.progress), FractionBaseline, TextStyle.Value, TextKind.Value)
    ) ++ notes.zipWithIndex.map { (note, index) =>
      line(note, FirstNoteBaseline + index * NoteLinePx, TextStyle.Body, TextKind.Value)
    }
