package dosecord.core.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Named pins for the adherence definitions of DESIGN.md section 7.3 and ADR-012 (ROADMAP M1.4b): the formula,
  * `effective_at` day assignment, corrections excluded from delay statistics, and streak neutral days.
  */
class AdherenceSuite extends munit.FunSuite:

  private val utc: ZoneId = ZoneId.of("UTC")
  private val scheduledFor: Instant = Instant.parse("2026-03-01T09:00:00Z")
  private val dueWindowEnd: Instant = scheduledFor.plusSeconds(3600)

  private def entry(
      status: OccurrenceStatus,
      effectiveAt: Option[Instant] = None,
      resolvedAt: Option[Instant] = None,
      scheduled: Instant = scheduledFor,
      manual: Boolean = false,
      corrected: Boolean = false
  ): AdherenceInput =
    AdherenceInput(scheduled, status, effectiveAt, resolvedAt, dueWindowEnd, manual, corrected)

  test("adherence is (taken_on_time + taken_late) / resolved"):
    val entries = List(
      entry(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(60))),
      entry(OccurrenceStatus.Taken, effectiveAt = Some(dueWindowEnd.plusSeconds(60))),
      entry(OccurrenceStatus.Skipped, resolvedAt = Some(scheduledFor.plusSeconds(120))),
      entry(OccurrenceStatus.Missed, resolvedAt = Some(scheduledFor.plusSeconds(7200)))
    )
    assertEquals(Adherence.counts(entries), AdherenceCounts(takenOnTime = 1, takenLate = 1, skipped = 1, missed = 1))
    assertEqualsDouble(Adherence.adherence(entries).get, 0.5, 1e-9)
    assertEqualsDouble(Adherence.onTimeRate(entries).get, 0.5, 1e-9)

  test("adherence and on-time rate are undefined on empty denominators"):
    assertEquals(Adherence.adherence(Nil), None)
    assertEquals(Adherence.onTimeRate(Nil), None)
    val onlyMissed = List(entry(OccurrenceStatus.Missed, resolvedAt = Some(scheduledFor.plusSeconds(7200))))
    assertEqualsDouble(Adherence.adherence(onlyMissed).get, 0.0, 1e-9)
    assertEquals(Adherence.onTimeRate(onlyMissed), None)

  test("unknown, cancelled, open and manual entries are excluded from the denominator"):
    val entries = List(
      entry(OccurrenceStatus.Unknown),
      entry(OccurrenceStatus.Cancelled),
      entry(OccurrenceStatus.Pending),
      entry(OccurrenceStatus.Due),
      entry(OccurrenceStatus.Snoozed),
      entry(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(60)), manual = true)
    )
    assertEquals(Adherence.counts(entries), AdherenceCounts.zero)
    assertEquals(Adherence.adherence(entries), None)

  test("the on-time boundary is due_window_end inclusive"):
    val onTime = entry(OccurrenceStatus.Taken, effectiveAt = Some(dueWindowEnd))
    val late = entry(OccurrenceStatus.Taken, effectiveAt = Some(dueWindowEnd.plusSeconds(1)))
    assertEquals(Adherence.counts(List(onTime)), AdherenceCounts(1, 0, 0, 0))
    assertEquals(Adherence.counts(List(late)), AdherenceCounts(0, 1, 0, 0))

  test("corrections are excluded from delay statistics but still count in adherence"):
    val plain = entry(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(300)))
    val corrected =
      entry(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(86400)), corrected = true)
    val stats = Adherence.delayStats(List(plain, corrected)).get
    assertEquals(stats.samples, 1)
    assertEquals(stats.median, Duration.ofMinutes(5))
    assertEquals(stats.p90, Duration.ofMinutes(5))
    assertEquals(Adherence.counts(List(plain, corrected)).taken, 2)

  test("median averages the middle pair, p90 is nearest-rank"):
    def taken(delaySeconds: Long) =
      entry(OccurrenceStatus.Taken, effectiveAt = Some(scheduledFor.plusSeconds(delaySeconds)))
    val four = List(60L, 120L, 180L, 240L).map(taken)
    val stats4 = Adherence.delayStats(four).get
    assertEquals(stats4.median, Duration.ofSeconds(150))
    assertEquals(stats4.p90, Duration.ofSeconds(240))
    val ten = (1L to 10L).map(m => taken(m * 60))
    val stats10 = Adherence.delayStats(ten).get
    assertEquals(stats10.median, Duration.ofSeconds(330))
    assertEquals(stats10.p90, Duration.ofMinutes(9))
    assertEquals(Adherence.delayStats(Nil), None)

  test("adherence day assignment uses effective_at, not scheduled_for"):
    val zone = utc
    val late23 = Instant.parse("2026-03-01T23:30:00Z")
    val takenAfterMidnight = Instant.parse("2026-03-02T00:45:00Z")
    val dose = AdherenceInput(
      scheduledFor = late23,
      status = OccurrenceStatus.Taken,
      effectiveAt = Some(takenAfterMidnight),
      resolvedAt = None,
      dueWindowEnd = late23.plusSeconds(3600),
      manual = false,
      corrected = false
    )
    assertEquals(Adherence.dayOf(dose, zone), Some(LocalDate.of(2026, 3, 2)))
    val day1 = Adherence.today(List(dose), Instant.parse("2026-03-01T23:45:00Z"), zone)
    assertEquals(day1.counts, AdherenceCounts.zero)
    val day2 = Adherence.today(List(dose), Instant.parse("2026-03-02T10:00:00Z"), zone)
    assertEquals(day2.counts, AdherenceCounts(0, 1, 0, 0))

  test("skipped and missed days are assigned by their resolution instant"):
    val skipped = entry(
      OccurrenceStatus.Skipped,
      resolvedAt = Some(scheduledFor.plusSeconds(26L * 3600L))
    )
    assertEquals(Adherence.dayOf(skipped, utc), Some(LocalDate.of(2026, 3, 2)))

  test("open doses count toward their scheduled day in today counts"):
    val open = entry(OccurrenceStatus.Due)
    val day = Adherence.today(List(open), scheduledFor.plusSeconds(600), utc)
    assertEquals(day.open, 1)
    assertEquals(day.counts, AdherenceCounts.zero)

  test("streaks: perfect days count, no-dose days are neutral, a failed day breaks"):
    def day(taken: Int, skipped: Int = 0, open: Int = 0) =
      DayAdherence(AdherenceCounts(taken, 0, skipped, 0), open)
    val d1 = LocalDate.of(2026, 3, 1)
    val d2 = LocalDate.of(2026, 3, 2)
    val d3 = LocalDate.of(2026, 3, 3)
    val d4 = LocalDate.of(2026, 3, 4)
    // Perfect on d1, no doses at all on d2 (absent from the map), perfect on d3 and d4: streak 3.
    val neutral = Map(d1 -> day(1), d3 -> day(2), d4 -> day(1))
    assertEquals(Adherence.streak(neutral, d4), 3)
    // An explicit no-dose day is neutral the same way.
    val explicitNeutral = Map(d1 -> day(1), d2 -> DayAdherence.zero, d3 -> day(1))
    assertEquals(Adherence.streak(explicitNeutral, d3), 2)
    // A skipped dose on d3 ends the streak: only d4 counts.
    val broken = Map(d1 -> day(1), d3 -> day(1, skipped = 1), d4 -> day(1))
    assertEquals(Adherence.streak(broken, d4), 1)
    // Today still in progress (open dose, nothing failed) is neutral and does not break yesterday's streak.
    val inProgress = Map(d1 -> day(1), d2 -> day(1), d3 -> day(1, open = 1))
    assertEquals(Adherence.streak(inProgress, d3), 2)
    // Days after `today` are ignored.
    assertEquals(Adherence.streak(neutral, d1), 1)
end AdherenceSuite
