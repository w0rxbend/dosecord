package dosecord.core.domain

/** ROADMAP M1.1: `ReminderPolicy` carries the ADR-012 / DESIGN.md section 7.1 defaults; snooze options are positive
  * and bounded.
  */
class ReminderPolicySuite extends munit.FunSuite:

  test("defaults are the ADR-012 / DESIGN.md section 7.1 values"):
    val policy = ReminderPolicy()
    assertEquals(policy.initialOffsetMinutes, 0)
    assertEquals(policy.repeatEveryMinutes, 10)
    assertEquals(policy.maxReminders, 3)
    assertEquals(policy.missAfterMinutes, 120)
    assertEquals(policy.onTimeGraceMinutes, 60)
    assertEquals(policy.snoozeOptionsMinutes, List(10, 30, 60))
    assertEquals(policy.maxSnoozes, 3)
    assertEquals(policy.missAfterSnoozeMinutes, 30)
    assertEquals(policy.maxLateMinutes, 360)
    assertEquals(policy.lateLogWindowMinutes, 1440)
    assertEquals(policy.undoWindowMinutes, 15)
    assertEquals(policy.quietHoursMode, QuietHoursMode.Deliver)
    assertEquals(policy.discreet, false)
    assertEquals(ReminderPolicy.Default, policy)

  test("salvaged prototype defaults match contracts.ReminderPolicyData"):
    val data = dosecord.contracts.ReminderPolicyData()
    val policy = ReminderPolicy()
    assertEquals(policy.initialOffsetMinutes, data.initialOffsetMinutes)
    assertEquals(policy.snoozeOptionsMinutes, data.snoozeOptionsMinutes)
    assertEquals(policy.missAfterMinutes, data.missAfterMinutes)
    assertEquals(policy.maxReminders, data.maxReminders)
    assertEquals(policy.discreet, data.discreet)

  test("snooze options must be positive"):
    intercept[IllegalArgumentException](ReminderPolicy(snoozeOptionsMinutes = List(10, 0)))
    intercept[IllegalArgumentException](ReminderPolicy(snoozeOptionsMinutes = List(10, -5)))

  test("snooze options must be bounded, non-empty and distinct"):
    intercept[IllegalArgumentException](ReminderPolicy(snoozeOptionsMinutes = List(10, 1441)))
    intercept[IllegalArgumentException](ReminderPolicy(snoozeOptionsMinutes = Nil))
    intercept[IllegalArgumentException](ReminderPolicy(snoozeOptionsMinutes = List(10, 10)))
    assertEquals(ReminderPolicy(snoozeOptionsMinutes = List(1440)).snoozeOptionsMinutes, List(1440))

  test("remaining fields are bounded"):
    intercept[IllegalArgumentException](ReminderPolicy(initialOffsetMinutes = -1441))
    intercept[IllegalArgumentException](ReminderPolicy(initialOffsetMinutes = 1441))
    intercept[IllegalArgumentException](ReminderPolicy(repeatEveryMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(maxReminders = 0))
    intercept[IllegalArgumentException](ReminderPolicy(maxReminders = 11))
    intercept[IllegalArgumentException](ReminderPolicy(missAfterMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(onTimeGraceMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(maxSnoozes = -1))
    intercept[IllegalArgumentException](ReminderPolicy(missAfterSnoozeMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(maxLateMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(lateLogWindowMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicy(undoWindowMinutes = 0))
end ReminderPolicySuite
