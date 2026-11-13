package dosegram.core.domain.sleep

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** PRODUCT_SPEC R-SLP-1, R-SLP-5, R-SLP-21 and AC-SLP-2: clock times become instants, and the length of a night is the
  * time that passed, on a 23-hour day and on a 25-hour day as on any other.
  */
class ClockNightsSuite extends munit.ScalaCheckSuite:

  private val berlin = ZoneId.of("Europe/Berlin")

  private def interval(onset: Instant, wake: Instant, zone: ZoneId): SleepInterval =
    SleepInterval(SleepEdge(onset, zone, EdgeSource.Retrospective), SleepEdge(wake, zone, EdgeSource.Retrospective))

  private def night(bed: String, wake: String, now: String, zone: ZoneId): SleepInterval =
    val (onsetAt, wakeAt) = ClockNights.latest(LocalTime.parse(bed), LocalTime.parse(wake), Instant.parse(now), zone)
    interval(onsetAt, wakeAt, zone)

  test("23:40 to 07:10 on an ordinary night is 7 h 30 min on the sleep day of the wake date (AC-SLP-1)"):
    val recorded = night("23:40", "07:10", "2026-11-12T09:00:00Z", berlin)
    assertEquals(recorded.duration, Duration.ofMinutes(450))
    assertEquals(recorded.sleepDay.toString, "2026-11-12")

  test("on the spring-forward night 23:00 to 07:00 lasts 7 hours (AC-SLP-2)"):
    val recorded = night("23:00", "07:00", "2026-03-29T10:00:00Z", berlin)
    assertEquals(recorded.duration, Duration.ofHours(7))
    assertEquals(recorded.sleepDay.toString, "2026-03-29")

  test("on the autumn night 23:00 to 07:00 lasts 9 hours (AC-SLP-2)"):
    val recorded = night("23:00", "07:00", "2026-10-25T10:00:00Z", berlin)
    assertEquals(recorded.duration, Duration.ofHours(9))
    assertEquals(recorded.sleepDay.toString, "2026-10-25")

  test("a wake time that has not come yet today belongs to the previous night"):
    val recorded = night("23:30", "07:10", "2026-11-12T05:00:00Z", berlin)
    assertEquals(recorded.sleepDay.toString, "2026-11-11")

  test("a bedtime after midnight stays on the wake date"):
    val recorded = night("00:40", "07:10", "2026-11-12T09:00:00Z", berlin)
    assertEquals(recorded.duration, Duration.ofMinutes(390))

  test("a clock time inside the spring gap is read as the time the clock showed instead"):
    // 02:30 does not exist on 29 March 2026 in Berlin: the clock went from 02:00 to 03:00.
    val at = ClockNights.latestAt(LocalTime.parse("02:30"), Instant.parse("2026-03-29T08:00:00Z"), berlin)
    assertEquals(at, Instant.parse("2026-03-29T01:30:00Z"))

  test("a clock time that exists twice on the autumn day is read as its first occurrence"):
    val at = ClockNights.latestAt(LocalTime.parse("02:30"), Instant.parse("2026-10-25T08:00:00Z"), berlin)
    assertEquals(at, Instant.parse("2026-10-25T00:30:00Z"))

  private val zones: Gen[ZoneId] = Gen
    .oneOf(
      "Europe/Berlin",
      "Europe/Kyiv",
      "America/New_York",
      "America/St_Johns",
      "Australia/Lord_Howe",
      "Asia/Kathmandu",
      "Pacific/Chatham",
      "America/Sao_Paulo",
      "UTC"
    )
    .map(ZoneId.of)

  private val clockTimes: Gen[LocalTime] =
    Gen.choose(0, 24 * 60 - 1).map(minute => LocalTime.of(minute / 60, minute % 60))

  // Every minute of 2026, so both transitions of every zone are generated.
  private val moments: Gen[Instant] =
    Gen.choose(0L, 365L * 24L * 60L - 1L).map(minute => Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L))

  // Around the two transitions of Berlin, where a night crosses a 23-hour or a 25-hour day.
  private val transitions: Gen[Instant] =
    for
      day <- Gen.oneOf("2026-03-29T00:00:00Z", "2026-10-25T00:00:00Z")
      minute <- Gen.choose(-12L * 60L, 36L * 60L)
    yield Instant.parse(day).plusSeconds(minute * 60L)

  private def exists(time: LocalTime, at: Instant, zone: ZoneId): Boolean =
    !zone.getRules.getValidOffsets(LocalDateTime.of(LocalDate.ofInstant(at, zone), time)).isEmpty

  property("a night from clock times ends at or before now, starts before it ends and is at most 25 hours long"):
    forAll(zones, clockTimes, clockTimes, moments): (zone, bed, wake, now) =>
      val (onsetAt, wakeAt) = ClockNights.latest(bed, wake, now, zone)
      val length = Duration.between(onsetAt, wakeAt)
      assert(!wakeAt.isAfter(now), s"wake $wakeAt is after now $now")
      assert(length.compareTo(Duration.ZERO) > 0 && length.compareTo(Duration.ofHours(25)) <= 0, s"length $length")

  property("each edge shows the clock time the user gave, unless that time does not exist on that day"):
    forAll(zones, clockTimes, clockTimes, moments): (zone, bed, wake, now) =>
      val (onsetAt, wakeAt) = ClockNights.latest(bed, wake, now, zone)
      List(bed -> onsetAt, wake -> wakeAt).foreach: (time, at) =>
        if exists(time, at, zone) then assertEquals(LocalTime.ofInstant(at, zone), time)

  property("the length is the clock difference corrected by the change of the zone offset between the edges"):
    forAll(Gen.oneOf(zones, Gen.const(berlin)), clockTimes, clockTimes, Gen.oneOf(moments, transitions)):
      (zone, bed, wake, now) =>
        val (onsetAt, wakeAt) = ClockNights.latest(bed, wake, now, zone)
        val rules = zone.getRules
        val onClock = Duration.between(LocalDateTime.ofInstant(onsetAt, zone), LocalDateTime.ofInstant(wakeAt, zone))
        val offsetChange = rules.getOffset(wakeAt).getTotalSeconds - rules.getOffset(onsetAt).getTotalSeconds
        assertEquals(Duration.between(onsetAt, wakeAt), onClock.minusSeconds(offsetChange.toLong))

  property("from a clock time to the same clock time across a clock change is 23 or 25 hours, never 24"):
    forAll(clockTimes.suchThat(_.isAfter(LocalTime.parse("03:00")))): time =>
      // Asked at local midnight after each Berlin transition day: the period ends on that day and began the day before.
      val spring = ClockNights.latest(time, time, Instant.parse("2026-03-29T22:00:00Z"), berlin)
      val autumn = ClockNights.latest(time, time, Instant.parse("2026-10-25T23:00:00Z"), berlin)
      assertEquals(Duration.between(spring._1, spring._2), Duration.ofHours(23))
      assertEquals(Duration.between(autumn._1, autumn._2), Duration.ofHours(25))

  property("the sleep day is the local date of the wake instant, whatever the offset at the onset was"):
    forAll(clockTimes, clockTimes, transitions): (bed, wake, now) =>
      val (onsetAt, wakeAt) = ClockNights.latest(bed, wake, now, berlin)
      assertEquals(interval(onsetAt, wakeAt, berlin).sleepDay, LocalDate.ofInstant(wakeAt, berlin))
end ClockNightsSuite
