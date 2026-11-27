package dosegram.api

import dosegram.core.domain.copy.Lang
import dosegram.core.domain.insights.DoseBuckets

/** The status words of the dose charts (BOT_UX section 22.4; VISUALS_AND_ANALYTICS, decision of 2026-11-27): an open
  * dose is "still to answer", as the chat's `/stats` says it, never "still due".
  */
class ChartWordsSuite extends munit.FunSuite:

  private val day = DoseBuckets(takenOnTime = 1, takenLate = 0, skipped = 0, missed = 1, unknown = 0, open = 2, asNeeded = 0)

  test("a day's mark names an open dose 'still to answer' and a missed one 'not confirmed'"):
    assertEquals(Lang.within(Lang.En)(Charts.dosesOf(day)), "1 of 4 taken, 1 not confirmed, 2 still to answer")
    assertEquals(Lang.within(Lang.Uk)(Charts.dosesOf(day)), "прийнято 1 з 4, не підтверджено 1, ще без відповіді — 2")

  test("the outcome bar names an open dose 'still to answer' in its marks and its table"):
    val chart = Lang.within(Lang.En)(Charts.doseOutcomes(day)).getOrElse(fail("a period with doses has the bar"))
    assert(chart.marks.contains("2 still to answer"), chart.marks.toString)
    assert(chart.table.rows.exists(_.headOption.contains("still to answer")), chart.table.toString)
    assert(!chart.toString.contains("still due"), chart.toString)
    val ukrainian = Lang.within(Lang.Uk)(Charts.doseOutcomes(day)).getOrElse(fail("a period with doses has the bar"))
    assert(ukrainian.marks.contains("2 ще без відповіді"), ukrainian.marks.toString)
