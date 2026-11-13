package dosegram.core.domain.hydration

import dosegram.core.domain.EntrySource

import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The bounds of PRODUCT_SPEC section 8.8. They guard input; none of them is guidance about an amount to drink. */
object HydrationBounds:
  val MinEntryMl: Int = 1
  val MaxEntryMl: Int = 5000
  val MinGoalMl: Int = 100
  val MaxGoalMl: Int = 10000
  val MaxEntriesPerDay: Int = 60
  val MaxPresets: Int = 4
  val DefaultPresetsMl: List[Int] = List(200, 300, 500)
  val UndoWindow: Duration = Duration.ofMinutes(15)

  /** One US fluid ounce in millilitres (PRODUCT_SPEC R-HYD-1). */
  val MillilitresPerFluidOunce: BigDecimal = BigDecimal("29.5735")

  def isEntry(millilitres: Int): Boolean = millilitres >= MinEntryMl && millilitres <= MaxEntryMl
  def isGoal(millilitres: Int): Boolean = millilitres >= MinGoalMl && millilitres <= MaxGoalMl

/** The unit the user entered an amount in. The `dbValue` strings match the `hydration_entries.entered_unit` CHECK. */
enum HydrationUnit(val dbValue: String):
  case Millilitre extends HydrationUnit("ml")
  case Litre extends HydrationUnit("l")
  case FluidOunce extends HydrationUnit("floz")
  case Glass extends HydrationUnit("glass")

object HydrationUnit:
  def fromDbValue(value: String): HydrationUnit =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown hydration unit '$value'"))

/** The value and unit exactly as the user gave them, so an entry is always shown the way it was made (R-HYD-1). */
final case class EnteredAmount(value: BigDecimal, unit: HydrationUnit)

/** The amount lies outside 1 to 5000 ml. The reply states the bounds of [[HydrationBounds]]. */
case object AmountOutOfBounds

/** An entry amount: canonical millilitres plus the entered form. */
final case class WaterAmount(millilitres: Int, entered: EnteredAmount):
  require(HydrationBounds.isEntry(millilitres), s"an entry amount is 1 to 5000 ml, got $millilitres")

object WaterAmount:

  /** Converts an entered amount to whole millilitres, rounding half up. A glass is `glassMl`, the user's own size. */
  def of(entered: EnteredAmount, glassMl: Int): Either[AmountOutOfBounds.type, WaterAmount] =
    val exact = entered.unit match
      case HydrationUnit.Millilitre => entered.value
      case HydrationUnit.Litre      => entered.value * 1000
      case HydrationUnit.FluidOunce => entered.value * HydrationBounds.MillilitresPerFluidOunce
      case HydrationUnit.Glass      => entered.value * glassMl
    val rounded = BigDecimal(exact.bigDecimal.setScale(0, RoundingMode.HALF_UP))
    if rounded < HydrationBounds.MinEntryMl || rounded > HydrationBounds.MaxEntryMl then Left(AmountOutOfBounds)
    else Right(WaterAmount(rounded.toIntExact, entered))

  def ofMillilitres(value: Int): Either[AmountOutOfBounds.type, WaterAmount] =
    if HydrationBounds.isEntry(value) then
      Right(WaterAmount(value, EnteredAmount(BigDecimal(value), HydrationUnit.Millilitre)))
    else Left(AmountOutOfBounds)

/** One immutable drink entry (ARCHITECTURE section 10.6). `localDate` and `zone` are fixed when the entry is made: a
  * later change of the account zone does not move it to another day (R-HYD-4).
  */
final case class HydrationEntry(
    id: UUID,
    accountId: UUID,
    occurredAt: Instant,
    localDate: LocalDate,
    zone: ZoneId,
    amount: WaterAmount,
    source: EntrySource,
    clientKey: Option[String],
    createdAt: Instant,
    voidedAt: Option[Instant]
):
  def isLive: Boolean = voidedAt.isEmpty

/** The user's own daily goal from `effectiveFrom` on; `None` means "no goal" from that day (R-HYD-2). */
final case class GoalChange(effectiveFrom: LocalDate, targetMl: Option[Int])
