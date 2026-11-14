package dosegram.core.domain.nutrition

import dosegram.core.domain.EntrySource

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The four meal types of PRODUCT_SPEC R-NUT-1. Drinks are not meals: they belong to hydration (R-NUT-9). `dbValue`
  * matches the `meals.meal_type` CHECK.
  */
enum MealType(val dbValue: String):
  case Breakfast extends MealType("breakfast")
  case Lunch extends MealType("lunch")
  case Dinner extends MealType("dinner")
  case Snack extends MealType("snack")

  /** Breakfast, lunch and dinner can be planned; a snack is counted on its own (VISUALS section 7.5). */
  def isMain: Boolean = this != Snack

object MealType:
  val main: List[MealType] = values.toList.filter(_.isMain)

  def fromDbValue(value: String): Option[MealType] = values.find(_.dbValue == value)

/** The optional words of a meal entry: 1 to [[MealText.MaxChars]] characters after trimming (R-NUT-1). It is class A
  * content: it is never analysed, never logged and never part of a figure.
  */
opaque type MealText = String

object MealText:
  val MaxChars: Int = 300

  /** `None` for blank text; `Left` for text over the bound, which is refused rather than cut. */
  def of(raw: String): Either[MealTextTooLong, Option[MealText]] =
    val trimmed = raw.trim
    if trimmed.isEmpty then Right(None)
    else if trimmed.codePointCount(0, trimmed.length) > MaxChars then Left(MealTextTooLong(MaxChars))
    else Right(Some(trimmed))

  extension (text: MealText) def value: String = text

/** Meal text over the bound of R-NUT-1. */
final case class MealTextTooLong(limit: Int)

object MealBounds:

  /** Undo on the outcome message works for 15 minutes (BOT_UX BUX-UNDO-01). */
  val UndoWindow: Duration = Duration.ofMinutes(15)

  /** A meal time may lie at most this far after the request: clocks of client and server differ a little. */
  val FutureSkew: Duration = Duration.ofMinutes(5)

/** One immutable meal entry (ARCHITECTURE section 10.7, entry columns of 10.1). A change is a new row that supersedes
  * the old one; the only change to a stored row is its void. No energy or nutrient value exists in this wave.
  */
final case class MealEntry(
    id: UUID,
    accountId: UUID,
    occurredAt: Instant,
    localDate: LocalDate,
    zone: ZoneId,
    mealType: MealType,
    text: Option[MealText],
    source: EntrySource,
    clientKey: Option[String],
    supersedesId: Option[UUID],
    createdAt: Instant,
    voidedAt: Option[Instant]
):
  def isLive: Boolean = voidedAt.isEmpty

/** The main meals the user plans to eat (VISUALS section 7.5): the regular-meal view counts these. An empty plan is
  * allowed; a day is then shown as a count, never as a rate.
  */
final case class MealPlan(planned: Set[MealType]):
  require(planned.forall(_.isMain), "only breakfast, lunch and dinner can be planned")

object MealPlan:
  val Default: MealPlan = MealPlan(MealType.main.toSet)
