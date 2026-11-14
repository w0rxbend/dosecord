package dosegram.render.card

import dosegram.render.scene.Align
import dosegram.render.scene.Channel
import dosegram.render.scene.MarkMeta
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathCommand
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Stroke
import dosegram.render.scene.TextKind
import dosegram.render.tokens.Domain
import dosegram.render.tokens.TextStyle

/** IMG-09, `card.food.day.v1`: the plate of one day. A circle in four slots, breakfast, lunch, dinner and snacks; a
  * logged slot is filled in the food hue with its time beside it, a slot without an entry is a dotted outline. The fill
  * and the outline differ in shape as well as colour, and every slot is named in words (VA-PR-06). There is no macro
  * bar: it needs typed numbers, which this wave does not have (ROADMAP G8.3). No colour or word rates a meal or the day
  * (VA-IMG-09a), and no meal word or photo is ever drawn.
  */
object FoodDayCard:
  val TemplateId = "card.food.day.v1"
  private val Center = Point(540, 488)
  private val Radius = 248.0
  private val GapPx = 12.0
  private val NameOffsetPx = 300.0
  private val NameRisePx = 132.0
  private val ValueDropPx = 56.0
  private val ColumnPx = 240.0
  private val SummaryBaseline = 884.0

  /** One slot of the plate: where it sits (its quadrant, from 12 o'clock clockwise) and what it shows. */
  final private case class Slot(name: String, value: Option[String], quadrant: Int)

  def render(food: FoodDayData, context: CardContext): CardOutput =
    val frame = CardFrame(context, CardFrame.Square, context.tokens.surface)
    val copy = context.copy
    val slots = Vector(
      Slot(copy.mainMeal(MainMeal.Breakfast), food.mains.get(MainMeal.Breakfast).map(copy.time), quadrant = 3),
      Slot(copy.mainMeal(MainMeal.Lunch), food.mains.get(MainMeal.Lunch).map(copy.time), quadrant = 0),
      Slot(copy.mainMeal(MainMeal.Dinner), food.mains.get(MainMeal.Dinner).map(copy.time), quadrant = 2),
      Slot(copy.snacksLabel, Option.when(food.snacks > 0)(copy.snackSlot(food.snacks)), quadrant = 1)
    )
    val summaryLook = TextLook(TextStyle.Value, frame.tokens.ink, Align.Middle, TextKind.Value)
    val nodes = frame.header(copy.foodTitle, copy.dayDate(food.date))
      ++ slots.map(slot => plateSlot(slot, frame))
      ++ slots.flatMap(slot => labels(slot, frame))
      ++ Vector(
        frame.text(copy.foodSummary(food), Point(Center.x, SummaryBaseline), summaryLook, frame.width),
        frame.footer
      )
    val text = TextEquivalent.of(copy.foodText(food), copy.foodDiscreet, context)
    CardOutput(frame.scene(TemplateId, nodes), text, "dosegram-food.png", context.privacy == Privacy.Discreet)

  /** A quarter of the plate, inset by half the gap on each straight edge so the slots read as four. */
  private def plateSlot(slot: Slot, frame: CardFrame): Node =
    val from = slot.quadrant * 90.0
    val to = from + 90.0
    val inset = GapPx / 2
    val corner = Point(Center.x + inset * sign(from + 45, sin = true), Center.y - inset * sign(from + 45, sin = false))
    val path = PathData(
      Vector(
        PathCommand.MoveTo(corner),
        PathCommand.LineTo(onRim(from, corner)),
        PathCommand.ArcTo(Radius, largeArc = false, clockwise = true, onRim(to, corner)),
        PathCommand.Close
      )
    )
    slot.value match
      case Some(_) =>
        val shape = Node.Path(path, Some(Paint.Solid(frame.tokens.hue(Domain.Food))), None)
        Node.Mark(MarkMeta("food.slot.logged", Set(Channel.Colour, Channel.Fill, Channel.Label)), Vector(shape))
      case None =>
        val shape = Node.Path(path, None, Some(Stroke.dotted(frame.tokens.ink3)))
        Node.Mark(MarkMeta("food.slot.open", Set(Channel.Pattern, Channel.Label)), Vector(shape))

  /** The slot's name and its time or count beside the plate, on the side of its quadrant. */
  private def labels(slot: Slot, frame: CardFrame): Vector[Node] =
    val isRight = slot.quadrant <= 1
    val isTop = slot.quadrant == 0 || slot.quadrant == 3
    val x = if isRight then frame.right else frame.left
    val align = if isRight then Align.End else Align.Start
    val nameBaseline = if isTop then Center.y - NameRisePx else Center.y + NameOffsetPx - NameRisePx
    val name = frame.text(
      slot.name,
      Point(x, nameBaseline),
      TextLook(TextStyle.BodyMedium, frame.tokens.ink, align, TextKind.Label),
      ColumnPx
    )
    val value = slot.value.map: content =>
      frame.text(
        content,
        Point(x, nameBaseline + ValueDropPx),
        TextLook(TextStyle.Value, frame.tokens.ink, align, TextKind.Value),
        ColumnPx
      )
    name +: value.toVector

  /** The point of the rim at `degrees` clockwise from 12 o'clock, shifted with the slot's inset corner. */
  private def onRim(degrees: Double, corner: Point): Point =
    val radians = StrictMath.toRadians(degrees)
    Point(corner.x + Radius * StrictMath.sin(radians), corner.y - Radius * StrictMath.cos(radians))

  private def sign(degrees: Double, sin: Boolean): Double =
    val radians = StrictMath.toRadians(degrees)
    StrictMath.signum(if sin then StrictMath.sin(radians) else StrictMath.cos(radians))
end FoodDayCard
