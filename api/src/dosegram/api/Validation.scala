package dosegram.api

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup

import java.time.LocalDate
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

/** A schedule draft and the engine rule, in both directions. Only the kinds the engine accepts exist here
  * (`Rule.isSupportedInMvp`). `today` is the account's local date: the default anchor of a periodic rule.
  */
object ScheduleRules:
  private val IsoDays = 1 to 7

  /** The most day groups of one fixed-times schedule: one per weekday. */
  val SlotGroupsMax = 7

  /** The optional fields of a draft, by wire name, and whether the draft sets each. */
  private val OptionalFields: List[(String, ScheduleDraft => Boolean)] = List(
    "times" -> (_.times.nonEmpty),
    "weekdays" -> (_.weekdays.nonEmpty),
    "maxPerDay" -> (_.maxPerDay.isDefined),
    "minGapMinutes" -> (_.minGapMinutes.isDefined),
    "everyDays" -> (_.everyDays.isDefined),
    "onDays" -> (_.onDays.isDefined),
    "offDays" -> (_.offDays.isDefined),
    "anchorDate" -> (_.anchorDate.isDefined),
    "everyWeeks" -> (_.everyWeeks.isDefined),
    "intervalMinutes" -> (_.intervalMinutes.isDefined),
    "startTime" -> (_.startTime.isDefined),
    "slotGroups" -> (_.slotGroups.nonEmpty)
  )

  /** The optional fields each pattern takes; any other field set on the draft is `not_allowed`. */
  private def allowed(pattern: SchedulePattern): Set[String] = pattern match
    case SchedulePattern.FixedTimes         => Set("times", "weekdays", "slotGroups")
    case SchedulePattern.AsNeeded           => Set("maxPerDay", "minGapMinutes")
    case SchedulePattern.EveryNDays         => Set("times", "everyDays", "anchorDate")
    case SchedulePattern.Cycle              => Set("times", "onDays", "offDays", "anchorDate")
    case SchedulePattern.EveryNWeeks        => Set("times", "weekdays", "everyWeeks", "anchorDate")
    case SchedulePattern.IntervalFixedStart => Set("startTime", "intervalMinutes", "maxPerDay", "weekdays")

  def validate(draft: ScheduleDraft, today: LocalDate): Either[ApiFailure, Rule] =
    val notAllowed = OptionalFields.collect:
      case (field, isSet) if isSet(draft) && !allowed(draft.pattern).contains(field) => FieldError(field, "not_allowed")
    val errors = draft.pattern match
      case SchedulePattern.FixedTimes =>
        if draft.slotGroups.isEmpty then timesErrors("times", draft.times) ++ weekdayErrors("weekdays", draft.weekdays)
        else slotGroupErrors(draft)
      case SchedulePattern.AsNeeded   => asNeededErrors(draft)
      case SchedulePattern.EveryNDays =>
        timesErrors("times", draft.times) ++ periodErrors("everyDays", draft.everyDays, Rule.MaxPeriodDays)
      case SchedulePattern.Cycle =>
        timesErrors("times", draft.times) ++ periodErrors("onDays", draft.onDays, Rule.MaxPeriodDays) ++
          periodErrors("offDays", draft.offDays, Rule.MaxPeriodDays)
      case SchedulePattern.EveryNWeeks =>
        timesErrors("times", draft.times) ++ requiredWeekdays("weekdays", draft.weekdays) ++
          periodErrors("everyWeeks", draft.everyWeeks, Rule.MaxPeriodWeeks)
      case SchedulePattern.IntervalFixedStart => intervalErrors(draft)
    val dateErrors =
      for
        start <- draft.startDate
        end <- draft.endDate
        if end.isBefore(start)
      yield FieldError("endDate", "before_start")
    val all = notAllowed ++ errors ++ dateErrors
    if all.nonEmpty then Left(ApiFailure.invalid(all)) else Right(ruleOf(draft, today))

  private def periodErrors(field: String, value: Option[Int], max: Int): List[FieldError] = value match
    case None                        => List(FieldError(field, "required"))
    case Some(n) if n < 1 || n > max => List(FieldError(field, "out_of_range"))
    case Some(_)                     => Nil

  private def timesErrors(field: String, times: List[String]): List[FieldError] =
    List(
      Option.when(times.isEmpty)(FieldError(field, "required")),
      Option.when(times.size > RegistryLimits.ScheduleTimesMax)(FieldError(field, "too_many")),
      Option.when(times.exists(HhMm.parse(_).isLeft))(FieldError(field, "invalid")),
      Option.when(times.distinct.size != times.size)(FieldError(field, "duplicate"))
    ).flatten

  private def weekdayErrors(field: String, weekdays: List[Int]): List[FieldError] =
    List(
      Option.when(!weekdays.forall(IsoDays.contains))(FieldError(field, "invalid")),
      Option.when(weekdays.distinct.size != weekdays.size)(FieldError(field, "duplicate"))
    ).flatten

  private def requiredWeekdays(field: String, weekdays: List[Int]): List[FieldError] =
    if weekdays.isEmpty then List(FieldError(field, "required")) else weekdayErrors(field, weekdays)

  /** Several day groups of one fixed-times schedule (ROADMAP G9.8): each names its days and times, and a weekday
    * belongs to one group at most (supervisor decision, 2026-11-15).
    */
  private def slotGroupErrors(draft: ScheduleDraft): List[FieldError] =
    val perGroup = draft.slotGroups.zipWithIndex.flatMap: (group, index) =>
      requiredWeekdays(s"slotGroups[$index].weekdays", group.weekdays) ++
        timesErrors(s"slotGroups[$index].times", group.times)
    val days = draft.slotGroups.flatMap(_.weekdays)
    List(
      Option.when(draft.times.nonEmpty)(FieldError("times", "not_allowed")),
      Option.when(draft.weekdays.nonEmpty)(FieldError("weekdays", "not_allowed")),
      Option.when(draft.slotGroups.size > SlotGroupsMax)(FieldError("slotGroups", "too_many")),
      Option.when(days.distinct.size != days.size)(FieldError("slotGroups", "overlap"))
    ).flatten ++ perGroup

  private def intervalErrors(draft: ScheduleDraft): List[FieldError] =
    val start = draft.startTime match
      case None                                  => List(FieldError("startTime", "required"))
      case Some(time) if HhMm.parse(time).isLeft => List(FieldError("startTime", "invalid"))
      case Some(_)                               => Nil
    val interval = draft.intervalMinutes match
      case None => List(FieldError("intervalMinutes", "required"))
      case Some(n) if n < Rule.MinIntervalMinutes || n > Rule.MaxIntervalMinutes =>
        List(FieldError("intervalMinutes", "out_of_range"))
      case Some(_) => Nil
    start ++ interval ++ periodErrors("maxPerDay", draft.maxPerDay, Rule.MaxTimesPerDay) ++
      weekdayErrors("weekdays", draft.weekdays)

  private def asNeededErrors(draft: ScheduleDraft): List[FieldError] =
    List(
      Option.when(draft.maxPerDay.exists(n => n < 1 || n > RegistryLimits.MaxPerDayMax))(
        FieldError("maxPerDay", "out_of_range")
      ),
      Option.when(draft.minGapMinutes.exists(n => n < 0 || n > RegistryLimits.MinGapMinutesMax))(
        FieldError("minGapMinutes", "out_of_range")
      )
    ).flatten

  // The as-needed defaults are those of the chat wizard (AddMedicationFlow).
  private def ruleOf(draft: ScheduleDraft, today: LocalDate): Rule = draft.pattern match
    case SchedulePattern.FixedTimes if draft.slotGroups.nonEmpty =>
      Rule.FixedTimes(draft.slotGroups.map(group => SlotGroup(days(group.weekdays), sorted(group.times))))
    case SchedulePattern.FixedTimes =>
      Rule.FixedTimes(List(SlotGroup(days(draft.weekdays), sorted(draft.times))))
    case SchedulePattern.AsNeeded =>
      Rule.AsNeeded(draft.maxPerDay.getOrElse(10), draft.minGapMinutes.getOrElse(0))
    case SchedulePattern.EveryNDays =>
      Rule.EveryNDays(draft.everyDays.get, sorted(draft.times), anchorOf(draft, today))
    case SchedulePattern.Cycle =>
      Rule.Cycle(draft.onDays.get, draft.offDays.get, sorted(draft.times), anchorOf(draft, today))
    case SchedulePattern.EveryNWeeks =>
      Rule.EveryNWeeks(draft.everyWeeks.get, days(draft.weekdays), sorted(draft.times), anchorOf(draft, today))
    case SchedulePattern.IntervalFixedStart =>
      Rule.IntervalFixedStart(
        HhMm.unsafe(draft.startTime.get),
        draft.intervalMinutes.get,
        draft.maxPerDay.get,
        days(draft.weekdays)
      )

  /** ISO weekdays as the engine keeps them, Monday first; an empty list is every day. */
  private def days(isoDays: List[Int]): List[Weekday] =
    (if isoDays.isEmpty then IsoDays.toList else isoDays.sorted).map(weekdayOf)

  private def sorted(times: List[String]): List[HhMm] = times.sorted.map(HhMm.unsafe)

  private def anchorOf(draft: ScheduleDraft, today: LocalDate): LocalDate =
    draft.anchorDate.orElse(draft.startDate).getOrElse(today)

  /** The builder's draft of a stored rule: the inverse of [[validate]] for every kind the engine accepts. One day group
    * reads as `times` and `weekdays`; several stay `slotGroups`, so the builder never flattens them.
    */
  def draftOf(rule: Rule): ScheduleDraft = rule match
    case Rule.FixedTimes(List(group)) =>
      ScheduleDraft(SchedulePattern.FixedTimes, times = clock(group.times), weekdays = isoDays(group.days))
    case Rule.FixedTimes(groups) =>
      ScheduleDraft(
        SchedulePattern.FixedTimes,
        slotGroups = groups.map(group => SlotGroupDraft(isoDays(group.days), clock(group.times)))
      )
    case Rule.AsNeeded(maxPerDay, minGapMinutes) =>
      ScheduleDraft(SchedulePattern.AsNeeded, maxPerDay = Some(maxPerDay), minGapMinutes = Some(minGapMinutes))
    case Rule.EveryNDays(everyDays, times, anchor) =>
      ScheduleDraft(
        SchedulePattern.EveryNDays,
        times = clock(times),
        everyDays = Some(everyDays),
        anchorDate = Some(anchor)
      )
    case Rule.Cycle(onDays, offDays, times, anchor) =>
      ScheduleDraft(
        SchedulePattern.Cycle,
        times = clock(times),
        onDays = Some(onDays),
        offDays = Some(offDays),
        anchorDate = Some(anchor)
      )
    case Rule.EveryNWeeks(everyWeeks, weekdays, times, anchor) =>
      ScheduleDraft(
        SchedulePattern.EveryNWeeks,
        times = clock(times),
        weekdays = isoDays(weekdays),
        everyWeeks = Some(everyWeeks),
        anchorDate = Some(anchor)
      )
    case Rule.IntervalFixedStart(anchor, intervalMinutes, maxPerDay, activeDays) =>
      ScheduleDraft(
        SchedulePattern.IntervalFixedStart,
        weekdays = isoDays(activeDays),
        maxPerDay = Some(maxPerDay),
        intervalMinutes = Some(intervalMinutes),
        startTime = Some(anchor.value)
      )
    case _: Rule.Chain | _: Rule.Taper | Rule.Paused =>
      throw IllegalStateException(s"rule kind ${Rule.kindTag(rule)} has no representation in the API")

  private def clock(times: List[HhMm]): List[String] = times.map(_.value).distinct.sorted
  private def weekdayOf(iso: Int): Weekday = Weekday.fromOrdinal(iso - 1)
  private def isoDays(days: List[Weekday]): List[Int] = days.map(_.ordinal + 1).distinct.sorted
end ScheduleRules
