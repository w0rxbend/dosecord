package dosegram.core.domain.copy

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup

/** A schedule rule in the words of the chat wizard, shared by the chat review card, the record summary and the API
  * (PRODUCT_SPEC R-SCH-5): `every day 09:00, 21:00`; several day groups read `on weekdays 08:00; on Sat, Sun 10:00`.
  * The caller names the words of an as-needed schedule, which differ by surface.
  */
object ScheduleWords:

  private val Workdays: List[Weekday] = Weekday.values.toList.take(5)

  def inWords(rule: Rule, asNeeded: => String): String = rule match
    case Rule.FixedTimes(List(group))              => groupWords(group)
    case Rule.FixedTimes(groups)                   => groups.map(groupWords).mkString("; ")
    case _: Rule.AsNeeded                          => asNeeded
    case Rule.EveryNDays(everyDays, times, anchor) =>
      s"${MedicationDepthCopy.everyNDays(everyDays, anchor)} ${clock(times)}"
    case Rule.Cycle(onDays, offDays, times, anchor) =>
      s"${MedicationDepthCopy.cycle(onDays, offDays, anchor)} ${clock(times)}"
    case Rule.EveryNWeeks(everyWeeks, days, times, anchor) =>
      s"${MedicationDepthCopy.everyNWeeks(everyWeeks, labels(days), anchor)} ${clock(times)}"
    case Rule.IntervalFixedStart(anchor, intervalMinutes, maxPerDay, days) =>
      val base = MedicationDepthCopy.interval(intervalMinutes, anchor.value, maxPerDay)
      if days.size == Weekday.values.length then base else s"$base, ${cadence(days)}"
    case _: Rule.Chain | _: Rule.Taper | Rule.Paused => Rule.kindTag(rule)

  /** The weekdays of a set in the wizard's words: every day, weekdays, or the listed days. */
  def cadence(days: List[Weekday]): String =
    val sorted = days.distinct.sortBy(_.ordinal)
    if sorted.size == Weekday.values.length then WizardCopy.cadenceEveryDay
    else if sorted == Workdays then WizardCopy.cadenceWeekdays
    else WizardCopy.cadenceDays(labels(sorted))

  private def groupWords(group: SlotGroup): String = s"${cadence(group.days)} ${clock(group.times)}"

  private def labels(days: List[Weekday]): List[String] =
    days.distinct.sortBy(_.ordinal).map(day => WizardCopy.weekdayLabels(day.ordinal))

  private def clock(times: List[HhMm]): String = times.map(_.value).distinct.sorted.mkString(", ")
end ScheduleWords
