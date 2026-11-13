package dosegram.core.application

import dosegram.contracts.*
import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.CancelReason
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ScheduleStatus
import dosegram.core.scheduling.Materialiser

import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** PRODUCT_SPEC R-IDC-3, R-IDC-8, R-IDC-9 and R-IDC-10 and BOT_UX BUX-CONS-03 for the medication domain in chat, on
  * the product composition over the in-memory ports: consent is captured at onboarding before the account exists, a
  * medication command or button without the consent answers with the consent prompt and records nothing, and a
  * withdrawal stops the reminders at once.
  */
class MedicationConsentSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val everyDay = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
  private val statement = ConsentCopy.statement(ConsentPurpose.Medication)

  private def withRig(test: MedicationWizardRig => Unit): Unit =
    ox.supervised(test(MedicationWizardRig(Profile.Telegram)))

  private def token(action: String, subject: UUID): String =
    MedicationWizardRig.codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, 0).wire

  private def onboard(rig: MedicationWizardRig, domain: String): Unit =
    rig.command("start")
    rig.tapLabel("Welcome to Dosegram", domain)
    rig.tapLabel("I agree that", ConsentCopy.Agree)
    rig.tapLabel("Please confirm", ConsentCopy.Confirm)
    rig.tapLabel("Pick your timezone", "UTC+03:00 — Europe/Kyiv")

  // ---------- Onboarding ----------

  test("onboarding stores nothing about the person until the flow completes, then the account with its consent"):
    withRig: rig =>
      val notice = rig.command("start")
      assert(notice.exists(_.contains("This is health data")), s"the notice card: $notice")
      assertEquals(rig.lastChoiceMap("Welcome to Dosegram").map(_.label).take(3), List("Medication", "Water", "Mood"))
      assertEquals(rig.tapLabel("Welcome to Dosegram", "Medication").take(1), List(statement))
      rig.clock.at = rig.clock.at.plusSeconds(40)
      val stageTwo = rig.tapLabel("I agree that", ConsentCopy.Agree)
      assertEquals(stageTwo.take(1), List(ConsentCopy.confirmation(ConsentPurpose.Medication)))
      rig.clock.at = rig.clock.at.plusSeconds(5)
      rig.tapLabel("Please confirm", ConsentCopy.Confirm)
      rig.tapLabel("Pick your timezone", "UTC+03:00 — Europe/Kyiv")
      assertEquals(rig.uow.accounts.all, Nil, "no account before the last step")
      assertEquals(rig.uow.consents.events.toList, Nil, "no ledger row before the account")

      val done = rig.tapLabel("It is", "Yes")
      assert(done.exists(_.startsWith("Consent recorded on 21 Sep 2026: medication records.")), s"the receipt: $done")
      assertEquals(rig.uow.accounts.all.size, 1)
      val (purpose, recorded, taps) = rig.uow.consents.grants.toList.head
      assertEquals(purpose, ConsentPurpose.Medication)
      assertEquals((recorded.locale, recorded.policyVersion, recorded.statement), ("en", ConsentCopy.NoticeVersion, statement))
      assertEquals(Duration.between(taps.agreedAt, taps.confirmedAt), Duration.ofSeconds(5), "both tap times are kept")

  test("onboarding asks consent for the chosen domain only; medication then opens with its own consent"):
    withRig: rig =>
      onboard(rig, "Water")
      rig.tapLabel("It is", "Yes")
      assertEquals(rig.uow.consents.events.toList, List("hydration:granted"))

      assertEquals(rig.command("meds"), List(statement), "the command answers with the consent prompt")
      rig.tapLabel("I agree that", ConsentCopy.Agree)
      val opened = rig.tapLabel("Please confirm", ConsentCopy.Confirm)
      assert(opened.exists(_.startsWith("Consent recorded on")), s"the receipt: $opened")
      assert(rig.lastChoiceMap("Medications").exists(_.label == "Add medication"), "the screen that was asked for opens")
      assertEquals(rig.uow.consents.events.toList, List("hydration:granted", "medication:granted"))

  test("an abandoned onboarding leaves no account and no consent"):
    withRig: rig =>
      rig.command("start")
      rig.tapLabel("Welcome to Dosegram", "Medication")
      rig.tapLabel("I agree that", ConsentCopy.Agree)
      rig.line("/cancel")
      assertEquals(rig.uow.accounts.all, Nil)
      assertEquals(rig.uow.consents.events.toList, Nil)
      assertEquals(rig.uow.sessions.all, Nil)

  // ---------- The gate (R-IDC-9) ----------

  test("without the consent every medication command answers with the consent prompt and records nothing"):
    withRig: rig =>
      rig.seedAccount(consents = Nil)
      List("/today", "/meds", "/history", "/taken", "/skip", "/snooze 10", "/log vitamin d").foreach: input =>
        assertEquals(rig.line(input), List(statement), input)
      assertEquals(rig.uow.medications.all, Nil)
      assertEquals(rig.uow.occurrences.all, Nil)
      assertEquals(rig.uow.doseActions.all, Nil)

  test("without the consent the medication entries of the menu and a dose button answer with the consent prompt"):
    withRig: rig =>
      val account = rig.seedAccount()
      val created = rig.seedSchedule(account, "Vitamin D", everyDay, kyiv)
      val dose = rig.seedOccurrence(created.scheduleId, 0, LocalTime.of(2, 0), "extra")
      rig.uow.consents.withdraw(account, ConsentPurpose.Medication, rig.clock.now())

      rig.command("menu")
      assertEquals(rig.tapLabel(MenuCopy.mainPrompt, "Medications"), List(statement))
      rig.command("menu")
      assertEquals(rig.tapLabel(MenuCopy.mainPrompt, "Today"), List(statement))
      assertEquals(rig.tap(token("dose.taken", dose)), List(statement), "a button of an old reminder")
      assertEquals(rig.tap(token("dose.log", created.medicationId)), List(statement))
      assertEquals(rig.uow.occurrences.all.find(_.id == dose).map(_.status), Some(OccurrenceStatus.Pending))
      assertEquals(rig.uow.doseActions.all.count(_.occurrenceId == dose), 0, "nothing was recorded")

  test("an add-medication wizard that finishes after the withdrawal saves nothing and says so"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.startWizardTelegram()
      rig.uow.consents.withdraw(account, ConsentPurpose.Medication, rig.clock.now())
      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.everyDay)
      val answer = rig.tapLabel("every day 09:00", Labels.Create)
      assertEquals(answer, List(ConsentCopy.medicationOff))
      assertEquals(rig.uow.medications.all, Nil)
      assertEquals(rig.uow.schedules.all, Nil)

  // ---------- Withdrawal (R-IDC-10) ----------

  test("withdrawing pauses every schedule and cancels every open dose, and the text says exactly that"):
    withRig: rig =>
      val account = rig.seedAccount()
      val created = rig.seedSchedule(account, "Vitamin D", everyDay, kyiv)
      val due = rig.seedOccurrence(created.scheduleId, 0, LocalTime.of(2, 0), "extra")
      assertEquals(rig.tickLoop(), 1, "the 02:00 dose is due at 03:00 local time")
      assert(rig.uow.occurrences.all.exists(_.status == OccurrenceStatus.Pending), "later doses are ahead")

      rig.command("privacy")
      val withdrawn = rig.tapLabel("Medication records", ConsentCopy.withdrawLabel(ConsentPurpose.Medication))
      assertEquals(withdrawn, List(ConsentCopy.withdrawn(ConsentPurpose.Medication)))
      assert(withdrawn.head.contains("every schedule is paused and every open dose is cancelled"), withdrawn.head)

      assertEquals(rig.uow.schedules.all.map(_.status), List(ScheduleStatus.Paused))
      val rows = rig.uow.occurrences.all
      assertEquals(rows.map(_.status).distinct, List(OccurrenceStatus.Cancelled), "no dose stays open")
      assertEquals(rows.find(_.id == due).flatMap(_.cancelReason), Some(CancelReason.ConsentWithdrawn))
      assertEquals(rig.uow.doseActions.all.count(_.newStatus == OccurrenceStatus.Cancelled), rows.size)

      // Nothing is scheduled while the consent is off (R-IDC-9): no new dose, no reminder.
      rig.clock.at = rig.clock.at.plus(Duration.ofDays(3))
      Materialiser(rig.uow, rig.clock).runOnce()
      assertEquals(rig.tickLoop(), 0)
      assertEquals(rig.uow.occurrences.all.size, rows.size, "the materialiser adds no dose to a paused schedule")
      assertEquals(rig.command("today"), List(statement), "the records are not shown")

  test("a new consent leaves the schedules paused until the user resumes them"):
    withRig: rig =>
      val account = rig.seedAccount()
      rig.seedSchedule(account, "Vitamin D", everyDay, kyiv)
      rig.command("privacy")
      rig.tapLabel("Medication records", ConsentCopy.withdrawLabel(ConsentPurpose.Medication))
      rig.command("meds")
      rig.tapLabel("I agree that", ConsentCopy.Agree)
      rig.tapLabel("Please confirm", ConsentCopy.Confirm)
      assertEquals(rig.uow.schedules.all.map(_.status), List(ScheduleStatus.Paused))
      assert(rig.lastChoiceMap("Medications").exists(_.label.startsWith("Resume")), "the menu offers Resume")
