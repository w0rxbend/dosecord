package dosegram.core.domain.copy

import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.TimeRef

import java.time.DayOfWeek
import java.time.LocalTime

/** A report preset is named by when it is sent: the verification of 2026-11-28 found `/reminders` naming the weekly
  * plan, Monday at 09:00, "Sunday evening".
  */
class ReportPresetCopySuite extends munit.FunSuite:

  test("the weekly summary preset is named by the day and time its plan sends it, in both languages"):
    assertEquals(PromptPresets.WeeklyReport.plan, NudgePlan.Weekly(DayOfWeek.MONDAY, TimeRef.At(LocalTime.of(9, 0))))
    assertEquals(Lang.within(Lang.En)(PromptCopy.presetName(PromptPresets.WeeklyReport.id)), "Monday at 09:00")
    assertEquals(Lang.within(Lang.Uk)(PromptCopy.presetName(PromptPresets.WeeklyReport.id)), "Понеділок о 09:00")
