package dosegram.api

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup

import scala.util.matching.Regex

/** The limits of the registry, published in the bootstrap document and in the option resources. */
object RegistryLimits:
  val NameMax = 80
  val InstructionsMax = 500
  val DoseUnitMax = 16
  val ScheduleTimesMax = 12
  val MaxPerDayMax = 24
  val MinGapMinutesMax = 1440

  val published: Limits = Limits(NameMax, InstructionsMax, DoseUnitMax, ScheduleTimesMax)

/** The validated editable set of a medication. */
final case class MedicationFields(
    name: String,
    doseAmount: Option[BigDecimal],
    doseUnit: Option[String],
    instructions: Option[String]
)

object MedicationFields:
  // MINI_APP 15.1: a decimal string with at most 3 fraction digits.
  private val Decimal: Regex = """^\d{1,6}(\.\d{1,3})?$""".r

  def validate(input: MedicationInput): Either[ApiFailure, MedicationFields] =
    val name = input.name.trim
    val unit = input.doseUnit.map(_.trim).filter(_.nonEmpty)
    val instructions = input.instructions.map(_.trim).filter(_.nonEmpty)
    val amount = input.doseAmount.map(_.trim).filter(_.nonEmpty)
    val errors = List(
      Option.when(name.isEmpty)(FieldError("name", "required")),
      Option.when(name.length > RegistryLimits.NameMax)(FieldError("name", "too_long")),
      Option.when(name.exists(_.isControl))(FieldError("name", "invalid")),
      Option.when(amount.exists(a => !Decimal.matches(a) || BigDecimal(a) <= 0))(FieldError("doseAmount", "invalid")),
      Option.when(unit.exists(_.length > RegistryLimits.DoseUnitMax))(FieldError("doseUnit", "too_long")),
      Option.when(unit.exists(_.exists(_.isControl)))(FieldError("doseUnit", "invalid")),
      Option.when(instructions.exists(_.length > RegistryLimits.InstructionsMax))(
        FieldError("instructions", "too_long")
      )
    ).flatten
    if errors.nonEmpty then Left(ApiFailure.invalid(errors))
    else Right(MedicationFields(name, amount.map(BigDecimal(_)), unit, instructions))

/** A schedule draft and the engine rule, in both directions. Only the kinds the engine accepts today exist here
  * (`Rule.isSupportedInMvp`).
  */
object ScheduleRules:
  private val IsoDays = 1 to 7

  def validate(draft: ScheduleDraft): Either[ApiFailure, Rule] =
    val errors = draft.pattern match
      case SchedulePattern.FixedTimes => fixedTimesErrors(draft)
      case SchedulePattern.AsNeeded   => asNeededErrors(draft)
    val dateErrors =
      for
        start <- draft.startDate
        end <- draft.endDate
        if end.isBefore(start)
      yield FieldError("endDate", "before_start")
    val all = errors ++ dateErrors
    if all.nonEmpty then Left(ApiFailure.invalid(all)) else Right(ruleOf(draft))

  private def fixedTimesErrors(draft: ScheduleDraft): List[FieldError] =
    List(
      Option.when(draft.times.isEmpty)(FieldError("times", "required")),
      Option.when(draft.times.size > RegistryLimits.ScheduleTimesMax)(FieldError("times", "too_many")),
      Option.when(draft.times.exists(HhMm.parse(_).isLeft))(FieldError("times", "invalid")),
      Option.when(draft.times.distinct.size != draft.times.size)(FieldError("times", "duplicate")),
      Option.when(!draft.weekdays.forall(IsoDays.contains))(FieldError("weekdays", "invalid")),
      Option.when(draft.weekdays.distinct.size != draft.weekdays.size)(FieldError("weekdays", "duplicate")),
      Option.when(draft.maxPerDay.isDefined)(FieldError("maxPerDay", "not_allowed")),
      Option.when(draft.minGapMinutes.isDefined)(FieldError("minGapMinutes", "not_allowed"))
    ).flatten

  private def asNeededErrors(draft: ScheduleDraft): List[FieldError] =
    List(
      Option.when(draft.times.nonEmpty)(FieldError("times", "not_allowed")),
      Option.when(draft.weekdays.nonEmpty)(FieldError("weekdays", "not_allowed")),
      Option.when(draft.maxPerDay.exists(n => n < 1 || n > RegistryLimits.MaxPerDayMax))(
        FieldError("maxPerDay", "out_of_range")
      ),
      Option.when(draft.minGapMinutes.exists(n => n < 0 || n > RegistryLimits.MinGapMinutesMax))(
        FieldError("minGapMinutes", "out_of_range")
      )
    ).flatten

  // The as-needed defaults are those of the chat wizard (AddMedicationFlow).
  private def ruleOf(draft: ScheduleDraft): Rule = draft.pattern match
    case SchedulePattern.FixedTimes =>
      val isoDays = if draft.weekdays.isEmpty then IsoDays.toList else draft.weekdays.sorted
      Rule.FixedTimes(List(SlotGroup(isoDays.map(weekdayOf), draft.times.sorted.map(HhMm.unsafe))))
    case SchedulePattern.AsNeeded =>
      Rule.AsNeeded(draft.maxPerDay.getOrElse(10), draft.minGapMinutes.getOrElse(0))

  /** The builder's view of a stored rule. Slot groups are flattened: the builder knows one set of days and times. */
  final case class RuleView(
      pattern: SchedulePattern,
      times: List[String],
      weekdays: List[Int],
      maxPerDay: Option[Int],
      minGapMinutes: Option[Int]
  )

  def view(rule: Rule): RuleView = rule match
    case Rule.FixedTimes(groups) =>
      val times = groups.flatMap(_.times).map(_.value).distinct.sorted
      val days = groups.flatMap(_.days).distinct.map(isoOf).sorted
      RuleView(SchedulePattern.FixedTimes, times, days, None, None)
    case Rule.AsNeeded(maxPerDay, minGapMinutes) =>
      RuleView(SchedulePattern.AsNeeded, Nil, Nil, Some(maxPerDay), Some(minGapMinutes))
    case _: Rule.EveryNDays | _: Rule.EveryNWeeks | _: Rule.Cycle | _: Rule.IntervalFixedStart | _: Rule.Chain |
        _: Rule.Taper | Rule.Paused =>
      throw IllegalStateException(s"rule kind ${Rule.kindTag(rule)} has no representation in the API")

  private def weekdayOf(iso: Int): Weekday = Weekday.fromOrdinal(iso - 1)
  private def isoOf(day: Weekday): Int = day.ordinal + 1
