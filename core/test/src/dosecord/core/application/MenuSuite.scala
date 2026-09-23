package dosecord.core.application

import dosecord.contracts.*
import dosecord.core.application.MedicationWizardRig.Profile
import dosecord.core.chat.CallbackMode
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.UnknownReason
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.ports.ScheduleStatus

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import scala.jdk.CollectionConverters.*

/** M1.9 menu acceptance on the in-memory ports: the main menu's shipped entries, the Medications submenu with the
  * pause/resume/archive toggle (revisions through the M1.5 lifecycle), the interactive `/today` (direct dose tokens,
  * as-needed log rows, the 8-row cap, the not-yet-recorded tap answer), the read-only `/history`, Medications ->
  * Settings showing the ADR-012 defaults, and Account -> Timezone moving only the following schedule.
  */
class MenuSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val utc = ZoneId.of("UTC")

  private def dailyAt(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  private def withRig(test: MedicationWizardRig => Unit): Unit =
    ox.supervised:
      test(MedicationWizardRig(Profile.Console))

  test("the main menu shows exactly the shipped entries"):
    withRig: rig =>
      rig.bootstrapConsole()
      rig.line("/menu")
      val menu = rig.lastChoiceMap("What would you like to do?")
      assertEquals(menu.map(_.label), List("Today", "Medications", "Account"))
      assert(!menu.exists(_.label == "Habits"), "unshipped entries are hidden by flag")
      assert(!menu.exists(_.label == "Reminders"))
      assert(!menu.exists(_.label == "Stats"))

  test("the Medications submenu shows the schedule, the toggle and no unshipped entries"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv, dose = (Some(BigDecimal(1000)), Some("IU")))
      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      val chunks = rig.lastChunks
      assert(chunks.exists(_.contains("Medications")), s"the submenu: $chunks")
      assert(chunks.exists(_.contains("Vitamin D 1000 IU — every day 09:00")), s"the schedule line: $chunks")
      val labels = rig.lastChoiceMap("Medications").map(_.label)
      assert(labels.contains("Pause"), s"the toggle: $labels")
      assert(labels.contains("Archive"))
      assert(labels.contains("Today's doses"))
      assert(labels.contains("Add medication"))
      assert(labels.contains("Settings"))
      assert(labels.contains("History"))
      assert(!labels.contains("Log dose"), "Log dose is hidden until M1.10")
      assert(!labels.contains("Edit"), "Edit is hidden until M3.2")

  test("pause then resume from the menu fires the next slot"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv)
      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      rig.tapLabel("Medications", "Pause")

      // The toggle line and the paused revisions.
      assert(rig.lastChunks.exists(_.contains("Paused since Mon")), s"paused line: ${rig.lastChunks}")
      val schedule = rig.uow.schedules.all.head
      assertEquals(schedule.status, ScheduleStatus.Paused)
      assertEquals(rig.uow.revisions.list(schedule.id).map(_.reason), List(Some("create"), Some("pause")))
      assert(rig.uow.occurrences.all.forall(_.status == OccurrenceStatus.Cancelled), "open rows cancelled paused")

      rig.tapLabel("Paused since Mon", "Resume")
      assert(
        rig.lastChunks.exists(_.contains("Resumed Vitamin D — next dose Mon 09:00")),
        s"resume notice: ${rig.lastChunks}"
      )
      assertEquals(rig.uow.schedules.all.head.status, ScheduleStatus.Active)
      assertEquals(
        rig.uow.revisions.list(schedule.id).map(_.reason),
        List(Some("create"), Some("pause"), Some("resume"))
      )
      // The next slot fired again: a pending occurrence for Mon 09:00 Kyiv (06:00Z).
      val open = rig.uow.occurrences.all.filter(_.status == OccurrenceStatus.Pending)
      assertEquals(open.map(_.scheduledFor), List(Instant.parse("2026-09-21T06:00:00Z"),
        Instant.parse("2026-09-22T06:00:00Z")))
      assert(open.forall(_.revision.contains(3)), "resumed under revision 3")

  test("archive asks once, then archives and hides the medication"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv)
      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      rig.tapLabel("Medications", "Archive")
      assert(
        rig.lastChunks.exists(_.contains("Archive Vitamin D? Its schedule stops; history is kept.")),
        s"the confirm card: ${rig.lastChunks}"
      )
      rig.tapLabel("Archive Vitamin D?", "Archive")
      assertEquals(rig.uow.schedules.all.head.status, ScheduleStatus.Archived)
      assert(rig.uow.occurrences.all.forall(_.status == OccurrenceStatus.Cancelled))
      assert(rig.lastChunks.exists(_.contains("Archived Vitamin D.")), s"the outcome: ${rig.lastChunks}")
      assert(rig.lastChunks.exists(_.contains("No medications yet.")), s"hidden from the submenu: ${rig.lastChunks}")

  test("Medications -> Settings shows the ADR-012 defaults"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv)
      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Medications")
      rig.tapLabel("Medications", "Settings")
      assert(
        rig.lastChunks.exists(_.contains(
          "Vitamin D — reminders at dose time; snooze 10m, 30m, 60m; marked missed after 120 min without a response."
        )),
        s"the policy line: ${rig.lastChunks}"
      )

  test("/today renders per-dose Taken/Skip and per-as-needed Log; a tap records nothing yet"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv,
        dose = (Some(BigDecimal(1000)), Some("IU")))
      rig.seedSchedule(account, "Vitamin C", Rule.AsNeeded(10, 0), kyiv)
      val today = rig.line("/today")
      assert(today.exists(_.contains("09:00 Vitamin D 1000 IU — scheduled")), s"the dose row: $today")
      assert(today.exists(_.contains("Vitamin C — as needed")), s"the as-needed row: $today")

      // The dose row's buttons are direct tokens on the occurrence (M1.10 will handle them).
      val takenWire = rig.lastChoiceMap("Today's doses").find(_.label == "Taken").map(_.callback).get
      val payload = MedicationWizardRig.codec.decode(takenWire).toOption.get
      assertEquals(payload.action.name, "dose.taken")
      assertEquals(payload.mode, CallbackMode.Direct)
      assertEquals(payload.subject, rig.uow.occurrences.all.head.id)
      val skipPayload = MedicationWizardRig.codec
        .decode(rig.lastChoiceMap("Today's doses").find(_.label == "Skip").map(_.callback).get).toOption.get
      assertEquals(skipPayload.action.name, "dose.skip")

      // A tap answers honestly and records nothing (the intake handlers land in M1.10); the toast goes to the
      // interaction handle, as an ephemeral vendor toast would.
      val (handle, _) = rig.tapWithHandle(takenWire)
      assert(
        handle.calls.asScala.exists(_.contains(MenuCopy.doseButtonsPending)),
        s"the pending answer: ${handle.calls}"
      )
      assertEquals(rig.uow.occurrences.all.head.status, OccurrenceStatus.Pending)
      assertEquals(rig.uow.doseActions.all, Nil, "no action rows")

      // The as-needed log button answers the same way.
      val logWire = rig.lastChoiceMap("Today's doses").find(_.label == "Log Vitamin C").map(_.callback).get
      val (logHandle, _) = rig.tapWithHandle(logWire)
      assert(
        logHandle.calls.asScala.exists(_.contains(MenuCopy.doseButtonsPending)),
        s"the log answer: ${logHandle.calls}"
      )
      assertEquals(rig.uow.occurrences.all.size, 2, "no manual occurrence yet")

  test("/today caps at 8 rows with a pointer to /history"):
    withRig: rig =>
      val account = rig.seedAccount()
      val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv)
      // Eight more doses today with distinct slot keys (09:00 is already materialised).
      (8 to 16).filter(_ != 9).foreach: hour =>
        rig.seedOccurrence(outcome.scheduleId, 0, LocalTime.of(hour, 0), f"t$hour%02d00")
      val today = rig.line("/today")
      val doseRows = today.flatMap(_.split("\n")).count(_.contains("Vitamin D"))
      assertEquals(doseRows, 8, s"the 8-row cap: $today")
      assert(today.exists(_.contains("+1 more — see /history.")), s"the overflow note: $today")

  test("/history shows the last 7 days per dose with status, effective time and taken_late"):
    withRig: rig =>
      val account = rig.seedAccount()
      val outcome = rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv,
        dose = (Some(BigDecimal(1000)), Some("IU")))
      val scheduleId = outcome.scheduleId
      // Six days back: taken on time; five: taken late; four: skipped; three: missed; two: unknown.
      rig.seedOccurrence(scheduleId, daysBack = 6, LocalTime.of(9, 0), "taken_on_time",
        _.copy(status = OccurrenceStatus.Taken, nextActionAt = None,
          takenAt = Some(Instant.parse("2026-09-15T06:10:00Z")),
          effectiveAt = Some(Instant.parse("2026-09-15T06:10:00Z"))))
      rig.seedOccurrence(scheduleId, daysBack = 5, LocalTime.of(9, 0), "taken_late",
        _.copy(status = OccurrenceStatus.Taken, nextActionAt = None,
          takenAt = Some(Instant.parse("2026-09-16T08:30:00Z")),
          effectiveAt = Some(Instant.parse("2026-09-16T08:30:00Z"))))
      rig.seedOccurrence(scheduleId, daysBack = 4, LocalTime.of(9, 0), "skipped",
        _.copy(status = OccurrenceStatus.Skipped, nextActionAt = None,
          skippedAt = Some(Instant.parse("2026-09-17T07:00:00Z"))))
      rig.seedOccurrence(scheduleId, daysBack = 3, LocalTime.of(9, 0), "missed",
        _.copy(status = OccurrenceStatus.Missed, nextActionAt = None,
          missedAt = Some(Instant.parse("2026-09-18T08:00:00Z"))))
      rig.seedOccurrence(scheduleId, daysBack = 2, LocalTime.of(9, 0), "unknown",
        _.copy(status = OccurrenceStatus.Unknown, nextActionAt = None,
          unknownReason = Some(UnknownReason.Outage)))

      val history = rig.line("/history")
      assert(history.exists(_.contains("Last 7 days")), s"the title: $history")
      // Day headers present.
      assert(history.exists(_.contains("Tue 15 Sep")), s"headers: $history")
      assert(history.exists(_.contains("Wed 16 Sep")))
      // Per dose: scheduled time, status, effective time, taken_late.
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — taken at 09:10")), s"on-time: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — taken at 11:30 (late)")), s"late: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — skipped at 10:00")), s"skipped: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — missed")), s"missed: $history")
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — unknown")), s"unknown: $history")
      // Today's pending dose is there too; only the last 7 days render (6 dose lines: 5 seeded + today).
      assert(history.exists(_.contains("09:00 Vitamin D 1000 IU — scheduled")), s"today: $history")
      assertEquals(history.flatMap(_.split("\n")).count(_.contains("09:00 Vitamin D 1000 IU —")), 6,
        s"7 days of doses: $history")

  test("Account -> Timezone moves the following schedule, leaves the non-following one, and echoes the time"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", dailyAt("09:00"), kyiv, tzFollowsUser = true)
      rig.seedSchedule(account, "Vitamin K", dailyAt("09:00"), utc, tzFollowsUser = false)

      rig.line("/menu")
      rig.tapLabel("What would you like to do?", "Account")
      rig.tapLabel("Account", "Timezone")
      rig.tapLabel("Pick your timezone", "UTC-04:00 — America/New_York")
      val out = rig.tapLabel("It is", Labels.Yes)
      assert(out.exists(_.contains("It is 20:00 for you now.")), s"the echo: $out")
      assertEquals(rig.uow.accounts.timezoneOf(account), Some("America/New_York"))

      // The following schedule moved to 09:00 New York (13:00Z).
      val following = rig.uow.schedules.all.find(_.tzFollowsUser).get
      assertEquals(following.tz.getId, "America/New_York")
      val followingOpen = rig.uow.occurrences.listBySchedule(following.id).filter(_.status.isOpen)
      assertEquals(followingOpen.map(_.scheduledFor), List(Instant.parse("2026-09-21T13:00:00Z"),
        Instant.parse("2026-09-22T13:00:00Z")))

      // The non-following schedule is untouched.
      val other = rig.uow.schedules.all.find(!_.tzFollowsUser).get
      assertEquals(other.tz.getId, "UTC")
      val otherOpen = rig.uow.occurrences.listBySchedule(other.id).filter(_.status.isOpen)
      assertEquals(otherOpen.map(_.scheduledFor), List(Instant.parse("2026-09-21T09:00:00Z"),
        Instant.parse("2026-09-22T09:00:00Z")))

  test("/help lists every command including /today, /history and itself"):
    withRig: rig =>
      rig.bootstrapConsole()
      val help = rig.line("/help")
      CommandRegistry.all.foreach: spec =>
        assert(help.exists(_.contains(s"/${spec.name}")), s"/help lists /${spec.name}")
      assert(help.exists(_.contains("/today")))
      assert(help.exists(_.contains("/history")))
      assert(help.exists(_.contains("/help")), "/help lists itself")
