package dosegram.core.domain.inventory

import dosegram.core.domain.Evaluator
import dosegram.core.domain.ScheduleRevision

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** When the refill notice is owed (PRODUCT_SPEC R-REG-11): at or below a number of days of supply, or of units. */
enum RefillThreshold:
  case Days(days: Int)
  case Units(units: BigDecimal)

object RefillThreshold:
  /** The default of R-REG-11 (PROPOSED). */
  val Default: RefillThreshold = Days(7)

/** The count the user keeps for one medication (`medication_inventory`, ROADMAP G9.10). `countOff` owes the user the
  * one notice of R-REG-16; `noticesStopped` is "Stop reminding for this pack".
  */
final case class Inventory(
    medicationId: UUID,
    accountId: UUID,
    onHand: BigDecimal,
    perIntake: BigDecimal,
    threshold: Option[RefillThreshold],
    lastRefill: Option[BigDecimal],
    countOff: Boolean,
    noticesStopped: Boolean,
    lastNoticeAt: Option[Instant],
    version: Int,
    updatedAt: Instant
)

/** How far the count reaches on the user's own schedule (PRODUCT_SPEC R-REG-13, VISUALS IMG-15). `daysOfSupply` is the
  * number of whole local days from today to the date of the first intake the count cannot cover; `lastCovered` is the
  * date of the last intake it covers. Both are empty when no scheduled intake falls inside the horizon that the count
  * cannot cover (as needed, paused without a resume date, or a count that outlasts the horizon).
  */
final case class Supply(intakesCovered: Int, daysOfSupply: Option[Int], lastCovered: Option[LocalDate])

object Supply:

  /** How far ahead the walk looks: a count that covers more than this is reported without a days figure. */
  val HorizonDays: Int = 400

  /** Walks the schedule forward from `now`, never dividing by an average (R-REG-13): the intakes still open today and
    * scheduled before `now` come first, then every reminder of `revisions` in time order. Each intake takes `perIntake`
    * from `onHand`; the first one the count cannot cover ends the walk.
    */
  def of(
      onHand: BigDecimal,
      perIntake: BigDecimal,
      revisions: List[ScheduleRevision],
      openEarlierToday: Int,
      now: Instant,
      zone: ZoneId
  ): Supply =
    require(perIntake > 0, "units per intake must be positive")
    val today = now.atZone(zone).toLocalDate
    val covered = (onHand / perIntake).setScale(0, BigDecimal.RoundingMode.FLOOR).toInt
    val horizon = now.plus(Duration.ofDays(HorizonDays.toLong))
    val ahead = revisions
      .flatMap(revision => Evaluator.occurrences(revision, now, horizon))
      .sortBy(_.scheduledFor)
      .map(_.scheduledFor.atZone(zone).toLocalDate)
    val intakes = LazyList.fill(openEarlierToday)(today) ++ ahead
    val firstUncovered = intakes.drop(covered).headOption
    val lastCovered = if covered == 0 then None else intakes.take(covered).lastOption
    Supply(
      covered,
      firstUncovered.map(date => ChronoUnit.DAYS.between(today, date).toInt),
      firstUncovered.flatMap(_ => lastCovered)
    )

  /** Whether the count is at or below the user's threshold. A days threshold needs a days figure: an as-needed
    * medication answers only to a units threshold (R-REG-13).
    */
  def belowThreshold(inventory: Inventory, supply: Supply): Boolean =
    inventory.threshold.exists:
      case RefillThreshold.Days(days)   => supply.daysOfSupply.exists(_ <= days)
      case RefillThreshold.Units(units) => inventory.onHand <= units
end Supply
