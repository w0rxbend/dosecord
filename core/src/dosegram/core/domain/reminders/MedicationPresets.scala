package dosegram.core.domain.reminders

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.domain.Evaluator
import dosegram.core.domain.Rule
import dosegram.core.domain.ScheduleRevision
import dosegram.core.domain.SlotGroup

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The choices a preset leaves open: the day and time of a weekly schedule, the days of a cycle. */
final case class MedicationPresetParams(
    weekday: Weekday = Weekday.Mon,
    time: Option[LocalTime] = None,
    onDays: Int = 21,
    offDays: Int = 7
)

/** A medication schedule preset (ADR-023): a name and the rule of the existing schedule model it builds from the day
  * profile. The times are taken from the anchors when the preset is applied; the schedule then keeps clock times, as
  * every schedule does. `materialised` is false for a rule the engine does not remind yet; such a preset is listed and
  * not offered. Every other day and cycles are reminded since ROADMAP G9.8.
  */
final case class MedicationPreset(id: String, materialised: Boolean)

object MedicationPresets:

  val Morning: MedicationPreset = MedicationPreset("med.morning", materialised = true)
  val Evening: MedicationPreset = MedicationPreset("med.evening", materialised = true)
  val TwiceDaily: MedicationPreset = MedicationPreset("med.twice_daily", materialised = true)
  val WithMeals: MedicationPreset = MedicationPreset("med.three_with_meals", materialised = true)
  val Every8Hours: MedicationPreset = MedicationPreset("med.every_8h", materialised = true)
  val Every12Hours: MedicationPreset = MedicationPreset("med.every_12h", materialised = true)
  val Weekdays: MedicationPreset = MedicationPreset("med.weekdays", materialised = true)
  val Weekly: MedicationPreset = MedicationPreset("med.weekly", materialised = true)
  val EveryOtherDay: MedicationPreset = MedicationPreset("med.every_other_day", materialised = true)
  val CycleOnOff: MedicationPreset = MedicationPreset("med.cycle", materialised = true)
  val AsNeeded: MedicationPreset = MedicationPreset("med.as_needed", materialised = true)

  val all: List[MedicationPreset] = List(
    Morning,
    Evening,
    TwiceDaily,
    WithMeals,
    Every8Hours,
    Every12Hours,
    Weekdays,
    Weekly,
    EveryOtherDay,
    CycleOnOff,
    AsNeeded
  )

  def byId(id: String): Option[MedicationPreset] = all.find(_.id == id)

  private val everyDay: List[Weekday] = Weekday.values.toList

  /** The rule of the preset for this day profile. `today` anchors every other day and a cycle. As needed has no
    * reminder.
    */
  def rule(preset: MedicationPreset, profile: DayProfile, params: MedicationPresetParams, today: LocalDate): Rule =
    def daily(times: LocalTime*): Rule = Rule.FixedTimes(List(SlotGroup(everyDay, clockTimes(times))))
    preset match
      case Morning      => daily(profile.wake)
      case Evening      => daily(profile.dinner)
      case TwiceDaily   => daily(profile.wake, profile.bedtime)
      case WithMeals    => daily(profile.breakfast, profile.lunch, profile.dinner)
      case Every8Hours  => daily(profile.wake, profile.wake.plusHours(8), profile.wake.plusHours(16))
      case Every12Hours => daily(profile.wake, profile.wake.plusHours(12))
      case Weekdays     => Rule.FixedTimes(List(SlotGroup(everyDay.take(5), clockTimes(List(profile.wake)))))
      case Weekly       =>
        Rule.FixedTimes(List(SlotGroup(List(params.weekday), clockTimes(List(params.time.getOrElse(profile.wake))))))
      case EveryOtherDay => Rule.EveryNDays(2, clockTimes(List(params.time.getOrElse(profile.wake))), today)
      case CycleOnOff    =>
        Rule.Cycle(params.onDays, params.offDays, clockTimes(List(params.time.getOrElse(profile.wake))), today)
      case AsNeeded => Rule.AsNeeded(maxPerDay = 10, minGapMinutes = 0)
      case other    => throw IllegalArgumentException(s"unknown medication preset ${other.id}")

  /** The next `limit` reminder instants of a rule, within eight days: the preview before the schedule is saved. */
  def preview(rule: Rule, zone: ZoneId, now: Instant, limit: Int): List[Instant] =
    Evaluator
      .occurrences(ScheduleRevision(rule, zone), now, now.plus(Duration.ofDays(8)))
      .map(_.scheduledFor)
      .take(limit)

  /** Wall times as the schedule stores them, sorted and without repeats (three anchors may coincide). */
  private def clockTimes(times: Seq[LocalTime]): List[HhMm] =
    times.map(t => HhMm.unsafe(f"${t.getHour}%02d:${t.getMinute}%02d")).distinct.sortBy(_.value).toList
