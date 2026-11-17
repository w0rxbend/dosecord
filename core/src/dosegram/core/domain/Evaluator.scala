package dosegram.core.domain

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One generated occurrence of a schedule revision, before persistence: DESIGN.md section 7.2's `Candidate` minus
  * `doseSnapshot`, which the M1.5 materialiser job fills from the medication row. Fits the `dose_occurrences` row
  * shape: the natural key `(local_date, slot_key)` plus `local_time`, `scheduled_for`, `dst_kind` and, through the
  * revision's zone, `tz`.
  */
final case class OccurrenceCandidate(
    localDate: LocalDate,
    localTime: HhMm,
    slotKey: String,
    scheduledFor: Instant,
    dstKind: DstKind
)

/** The schedule-revision value the pure evaluator expands (ROADMAP M1.2): rule, IANA zone, reminder policy and the
  * half-open validity window `[effectiveFrom, effectiveUntil)` over local dates in the revision's zone. Identity fields
  * (`schedule_id`, `revision` number) arrive with persistence in M1.5.
  */
final case class ScheduleRevision(
    rule: Rule,
    zone: ZoneId,
    policy: ReminderPolicy = ReminderPolicy.Default,
    effectiveFrom: LocalDate = LocalDate.of(1970, 1, 1),
    effectiveUntil: Option[LocalDate] = None
):
  def covers(date: LocalDate): Boolean =
    !date.isBefore(effectiveFrom) && effectiveUntil.forall(date.isBefore)

/** Pure occurrence generation for schedule revisions (DESIGN.md section 7.2, ADR-004/011): expands local dates in the
  * revision's zone through M0.7's `Dst.resolveLocal`. No I/O, no clock — callers pass the window.
  */
object Evaluator:

  /** The rolling materialisation window (DESIGN.md section 7.2): callers pass `[now, now + horizon)` as `from`/`to`.
    */
  val MaterialisationHorizon: Duration = Duration.ofHours(48)

  /** The `slot_key` of a fixed wall time, stable across revisions while the wall time is unchanged (DESIGN.md section
    * 7.1): `t0900` for 09:00. The other schemes of that section (`int:`, `chain:`, `manual:`) are produced by their own
    * kinds, never here.
    */
  def fixedSlotKey(t: HhMm): String = f"t${t.hour}%02d${t.minute}%02d"

  /** All candidates of `revision` whose `scheduledFor` falls in the half-open instant window `[from, to)`, sorted by
    * `(scheduledFor, localDate, slotKey)` and deduplicated on the natural key `(localDate, slotKey)` — the same
    * arbitration the `ON CONFLICT DO NOTHING` insert applies, so recomputing a window yields the identical row set
    * (idempotent re-materialisation).
    *
    * `AsNeeded` produces nothing: as-needed doses are manual-log driven (`manual:{uuid}` keys are created at log time,
    * DESIGN.md section 7.1/7.2). `EveryNDays`, `EveryNWeeks` and `Cycle` expand on their dates like fixed times;
    * `IntervalFixedStart` re-anchors on each active date and keys its doses `int:{k}` (ROADMAP G9.8). `Chain`, `Taper`
    * and `Paused` are declared, inert and materialise nothing (ROADMAP M1.1, ADR-004). Exhaustive on purpose: a new
    * [[Rule]] case without an arm here fails compilation.
    */
  def occurrences(revision: ScheduleRevision, from: Instant, to: Instant): List[OccurrenceCandidate] =
    require(from.isBefore(to), s"window start $from must be before its end $to")
    val generated =
      for
        date <- localDatesCovering(from, to, revision.zone) if revision.covers(date)
        candidate <- candidatesOn(revision, date)
        if !candidate.scheduledFor.isBefore(from) && candidate.scheduledFor.isBefore(to)
      yield candidate
    generated
      .distinctBy(candidate => (candidate.localDate, candidate.slotKey))
      .sortBy(candidate =>
        (
          candidate.scheduledFor.getEpochSecond,
          candidate.scheduledFor.getNano,
          candidate.localDate.toEpochDay,
          candidate.slotKey
        )
      )

  private def candidatesOn(revision: ScheduleRevision, date: LocalDate): List[OccurrenceCandidate] =
    revision.rule match
      case Rule.FixedTimes(slotGroups) =>
        val day = weekdayOf(date)
        for
          group <- slotGroups if group.days.contains(day)
          time <- group.times
          (instant, kind) = Dst.resolveLocal(date, LocalTime.of(time.hour, time.minute), revision.zone)
        yield OccurrenceCandidate(date, time, fixedSlotKey(time), instant, kind)
      case Rule.AsNeeded(_, _) => Nil // manual-log driven: no scheduled occurrences (DESIGN.md section 7.1/7.2)
      case Rule.EveryNDays(everyDays, times, anchor) =>
        if Math.floorMod(ChronoUnit.DAYS.between(anchor, date), everyDays.toLong) == 0 then
          wallTimes(revision, date, times)
        else Nil
      case Rule.Cycle(onDays, offDays, times, anchor) =>
        val dayOfCycle = Math.floorMod(ChronoUnit.DAYS.between(anchor, date), (onDays + offDays).toLong)
        if dayOfCycle < onDays then wallTimes(revision, date, times) else Nil
      case Rule.EveryNWeeks(everyWeeks, days, times, anchor) =>
        val weeks = ChronoUnit.WEEKS.between(mondayOf(anchor), mondayOf(date))
        if days.contains(weekdayOf(date)) && Math.floorMod(weeks, everyWeeks.toLong) == 0 then
          wallTimes(revision, date, times)
        else Nil
      case rule: Rule.IntervalFixedStart =>
        if rule.activeDays.contains(weekdayOf(date)) then intervalDoses(revision.zone, date, rule) else Nil
      // Declared, inert until their slice (ROADMAP G12.8); a Paused revision materialises nothing (ADR-004).
      case _: Rule.Chain => Nil
      case _: Rule.Taper => Nil
      case Rule.Paused   => Nil

  /** The `slot_key` of the k-th dose of a day of an interval rule (DESIGN.md section 7.1). */
  def intervalSlotKey(k: Int): String = s"int:$k"

  /** The doses of one active date of an interval rule: the anchor resolved like a fixed time (gap moved forward, fold
    * the first instant), then every `intervalMinutes` of elapsed time, at most `maxPerDay`, and strictly before the
    * next date's anchor, so the last gap of a day is shorter or longer on a clock change (PRODUCT_SPEC R-SCH-13).
    */
  private def intervalDoses(zone: ZoneId, date: LocalDate, rule: Rule.IntervalFixedStart): List[OccurrenceCandidate] =
    val anchorTime = LocalTime.of(rule.anchorTime.hour, rule.anchorTime.minute)
    val (anchor, anchorKind) = Dst.resolveLocal(date, anchorTime, zone)
    val nextAnchor = Dst.resolveLocal(date.plusDays(1), anchorTime, zone)._1
    (0 until rule.maxPerDay).toList
      .map(k => k -> anchor.plus(Duration.ofMinutes(k.toLong * rule.intervalMinutes)))
      .takeWhile((_, at) => at.isBefore(nextAnchor))
      .map: (k, at) =>
        if k == 0 then OccurrenceCandidate(date, rule.anchorTime, intervalSlotKey(0), at, anchorKind)
        else
          val wall = at.atZone(zone).toLocalTime
          OccurrenceCandidate(
            date,
            HhMm.unsafe(f"${wall.getHour}%02d:${wall.getMinute}%02d"),
            intervalSlotKey(k),
            at,
            DstKind.None
          )

  private def mondayOf(date: LocalDate): LocalDate = date.minusDays(date.getDayOfWeek.getValue.toLong - 1)

  /** One candidate per wall time on `date`, keyed like a fixed time so the slot key survives a revision. */
  private def wallTimes(revision: ScheduleRevision, date: LocalDate, times: List[HhMm]): List[OccurrenceCandidate] =
    times.map: time =>
      val (instant, kind) = Dst.resolveLocal(date, LocalTime.of(time.hour, time.minute), revision.zone)
      OccurrenceCandidate(date, time, fixedSlotKey(time), instant, kind)

  /** Local dates whose candidates can land in `[from, to)`: the dates of the window bounds plus a two-day margin on
    * each side, because a gap resolution shifts an instant forward by up to the gap length (a full day for the 2011
    * Apia dateline skip) and offsets reach +/-14 h. Filtering by instant keeps the window exact and composable.
    */
  private def localDatesCovering(from: Instant, to: Instant, zone: ZoneId): List[LocalDate] =
    val first = from.atZone(zone).toLocalDate.minusDays(2)
    val last = to.atZone(zone).toLocalDate.plusDays(2)
    Iterator.iterate(first)(_.plusDays(1)).takeWhile(!_.isAfter(last)).toList

  private def weekdayOf(date: LocalDate): Weekday =
    date.getDayOfWeek match
      case DayOfWeek.MONDAY    => Weekday.Mon
      case DayOfWeek.TUESDAY   => Weekday.Tue
      case DayOfWeek.WEDNESDAY => Weekday.Wed
      case DayOfWeek.THURSDAY  => Weekday.Thu
      case DayOfWeek.FRIDAY    => Weekday.Fri
      case DayOfWeek.SATURDAY  => Weekday.Sat
      case DayOfWeek.SUNDAY    => Weekday.Sun
end Evaluator
