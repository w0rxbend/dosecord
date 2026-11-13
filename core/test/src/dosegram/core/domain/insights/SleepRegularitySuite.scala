package dosegram.core.domain.insights

import dosegram.core.domain.EntrySource
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** VISUALS_AND_ANALYTICS section 6.13 (IMG-12) and the consistency rows of section 7.7: bars on the 18:00 to 18:00
  * axis, the circular standard deviation and median of the clock times, and the gates.
  */
class SleepRegularitySuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val end = LocalDate.of(2026, 11, 12)
  private val window = DateRange.endingOn(end, SleepRegularity.WindowDays)

  private def episode(wakeDay: LocalDate, bed: LocalTime, wake: LocalTime, kind: SleepKind = SleepKind.Main) =
    val wakeAt = wakeDay.atTime(wake).atZone(kyiv).toInstant
    val bedDay = if bed.isAfter(wake) then wakeDay.minusDays(1) else wakeDay
    val onsetAt = bedDay.atTime(bed).atZone(kyiv).toInstant
    SleepEpisode(
      UUID.randomUUID(),
      UUID.randomUUID(),
      kind,
      SleepInterval(SleepEdge(onsetAt, kyiv, EdgeSource.CheckIn), SleepEdge(wakeAt, kyiv, EdgeSource.CheckIn)),
      SleepOrigin.CheckIn,
      SleepTier.Explicit,
      Confidence.BothExplicit,
      None,
      EntrySource.Bot,
      None,
      None,
      wakeAt,
      None
    )

  test("a night is a bar from its bedtime to its wake time on the axis that starts at 18:00 the evening before"):
    val view = SleepRegularity.view(List(episode(end, LocalTime.of(23, 30), LocalTime.of(7, 0))), window, None)
    assertEquals(view.bars.map(bar => (bar.date, bar.fromMinute, bar.toMinute, bar.isNap)), List((end, 330, 780, false)))
    assertEquals(view.nights, 1)

  test("the spread of bedtimes is the circular standard deviation, and it crosses midnight without a jump"):
    assertEqualsDouble(SleepRegularity.circularSd(List(23 * 60 + 30, 30)), 30.0, 0.5)
    assertEqualsDouble(SleepRegularity.circularSd(List(600, 600, 600)), 0.0, 0.01)
    assertEquals(SleepRegularity.circularMedian(List(23 * 60, 23 * 60 + 30, 60)), 23 * 60 + 30)

  test("averages and spreads are withheld below 10 of 14 nights, and a nap is drawn but not counted"):
    val nine = (0 until 9).map(back => episode(end.minusDays(back.toLong), LocalTime.of(23, 0), LocalTime.of(7, 0))).toList
    val nap = episode(end, LocalTime.of(14, 0), LocalTime.of(14, 30), SleepKind.Nap)
    val few = SleepRegularity.view(nap :: nine, window, Some(450))
    assertEquals((few.nights, few.averageMinutes, few.bedtimeSpreadMinutes, few.typicalBedtime), (9, None, None, None))
    assertEquals(few.bars.count(_.isNap), 1)
    val ten = episode(end.minusDays(9), LocalTime.of(0, 0), LocalTime.of(8, 0)) :: nine
    val enough = SleepRegularity.view(ten, window, None)
    assertEquals(enough.bedtimeSpreadMinutes.isDefined && enough.wakeSpreadMinutes.isDefined, true)
    assertEquals(enough.averageMinutes, Some(480))
    assertEquals(enough.typicalBedtime, Some(23 * 60))
