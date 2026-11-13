package dosegram.core.domain.insights

import dosegram.core.domain.EntrySource
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** VISUALS_AND_ANALYTICS section 7.7: the night of a sleep day, its duration as a difference of instants, and the mean
  * duration behind the weekly and monthly gates.
  */
class SleepStatsSuite extends munit.FunSuite:

  import TrackingGens.*

  private val kyiv = ZoneId.of("Europe/Kyiv")

  private def episode(wakeDay: LocalDate, minutes: Int, kind: SleepKind = SleepKind.Main): SleepEpisode =
    val wake = wakeDay.atTime(LocalTime.of(7, 0)).atZone(kyiv).toInstant
    val onset = wake.minusSeconds(minutes * 60L)
    SleepEpisode(
      id = UUID.randomUUID(),
      accountId = UUID.randomUUID(),
      kind = kind,
      interval = SleepInterval(SleepEdge(onset, kyiv, EdgeSource.CheckIn), SleepEdge(wake, kyiv, EdgeSource.CheckIn)),
      origin = SleepOrigin.CheckIn,
      tier = SleepTier.Explicit,
      confidence = Confidence.BothExplicit,
      quality = None,
      source = EntrySource.Bot,
      clientKey = None,
      supersedesId = None,
      createdAt = wake,
      voidedAt = None
    )

  private def nights(minutes: Int*): Map[LocalDate, SleepNight] =
    SleepStats.byDay(minutes.zipWithIndex.map((length, back) => episode(windowEnd.minusDays(back.toLong), length)))

  test("a night belongs to the day it ends on and lasts the minutes between its instants"):
    val days = SleepStats.byDay(List(episode(windowEnd, 430)))
    assertEquals(days, Map(windowEnd -> SleepNight(430, None)))

  test("a rated night carries its rating; a nap and a voided night are not nights"):
    val rated = episode(windowEnd, 400).copy(quality = Quality.of(4))
    val nap = episode(windowEnd.minusDays(1), 40, SleepKind.Nap)
    val voided = episode(windowEnd.minusDays(2), 420).copy(voidedAt = Some(Instant.parse("2026-07-01T00:00:00Z")))
    assertEquals(SleepStats.byDay(List(rated, nap, voided)), Map(windowEnd -> SleepNight(400, Some(4))))

  test("a week needs three nights for an average, a month ten"):
    assertEquals(SleepStats.period(nights(420, 400), week).averageMinutes, Stat.Insufficient(2, 3, week))
    assertEquals(SleepStats.period(nights(420, 400, 380), week).averageMinutes.shown, Some(400.0))
    assertEquals(SleepStats.period(nights(List.fill(9)(420)*), month).averageMinutes, Stat.Insufficient(9, 10, month))

  test("a night outside the window is left out"):
    val period = SleepStats.period(nights(420, 400, 380, 360, 340, 320, 300, 280), week)
    assertEquals(period.nights, 7)
