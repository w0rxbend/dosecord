package dosecord.core.application

import dosecord.contracts.*
import dosecord.core.application.MedicationWizardRig.Profile
import dosecord.core.chat.CallbackMode
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.domain.copy.WizardCopy

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/** M1.9 acceptance on `FakeAdapter` over the in-memory ports: the add-medication wizard transcript goldens on the
  * console and discord profiles, restart survival at every step, the `[Back]` pre-filled form, `/cancel` leaving no
  * row, the 48 h materialisation, instructions on the reminder's second line (and NULL when skipped), find-or-create,
  * and the multi-select day picker on both tiers. The Postgres half is `infra`'s `MedicationWizardPgSuite`.
  */
class MedicationWizardSuite extends munit.FunSuite:

  test("wizard transcript (golden, console) and row-level effects"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      val transcript = rig.drive(MedicationWizardRig.ConsoleScript)

      assertEquals(
        transcript,
        MedicationWizardRig.expectedGolden(Profile.Console),
        "transcript drifted; regenerate with " +
          "./mill core.test.runMain dosecord.core.application.medicationWizardGoldenGenerate"
      )

      // One medication with the profile captured (R11).
      assertEquals(rig.uow.medications.all.size, 1)
      val medication = rig.uow.medications.all.head
      assertEquals(medication.name, "Vitamin D")
      assertEquals(medication.doseAmount, Some(BigDecimal(1000)))
      assertEquals(medication.doseUnit, Some("IU"))
      assertEquals(medication.instructions, Some("with breakfast"))

      // One schedule, following the user's timezone, revision 1 with the wizard's rule (R13).
      assertEquals(rig.uow.schedules.all.size, 1)
      val schedule = rig.uow.schedules.all.head
      assertEquals(schedule.kind, "fixed_times")
      assertEquals(schedule.tz.getId, "Europe/Kyiv")
      assert(schedule.tzFollowsUser)
      val revision = rig.uow.revisions.all.head
      assertEquals(revision.revision, 1)
      assertEquals(
        revision.rule,
        Rule.FixedTimes(List(SlotGroup(dosecord.contracts.Weekday.values.toList, List(dosecord.contracts.HhMm
          .unsafe("09:00")))))
      )

      // dose_occurrences rows exist for the next 48 h after confirm (Mon 09:00 and Tue 09:00 Kyiv).
      val occurrences = rig.uow.occurrences.listBySchedule(schedule.id)
      assertEquals(
        occurrences.map(_.scheduledFor),
        List(Instant.parse("2026-09-21T06:00:00Z"), Instant.parse("2026-09-22T06:00:00Z"))
      )
      assert(occurrences.forall(_.status == OccurrenceStatus.Pending))
      assert(occurrences.forall(_.state.nextActionAt.isDefined))

      // Exactly one schedule_created.v1 with the account id.
      val created = rig.uow.domainEvents.all.filter(_.eventType == Event.ScheduleCreatedType)
      assertEquals(created.size, 1)
      assertEquals(created.head.accountId, Some(medication.accountId))

      // No leftover session.
      assertEquals(rig.uow.sessions.all, Nil)

  test("wizard transcript (golden, discord): one modal for step 1, buttons elsewhere"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Discord)
      val transcript = rig.drive(MedicationWizardRig.DiscordScript)

      assertEquals(
        transcript,
        MedicationWizardRig.expectedGolden(Profile.Discord),
        "transcript drifted; regenerate with " +
          "./mill core.test.runMain dosecord.core.application.medicationWizardGoldenGenerate"
      )

      // Discord saw exactly one modal (step 1), opened through the un-acked menu interaction.
      val modals = rig.openedForms
      assertEquals(modals.size, 1)
      assertEquals(modals.head.id, "medication.add")
      assertEquals(modals.head.fields.map(_.key), List("name", "dose", "times", "instructions"))

      assertEquals(rig.uow.medications.all.size, 1)
      assertEquals(rig.uow.occurrences.all.size, 2)

  test("the wizard survives a restart at the form step"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.line("/menu")
      rig.line("2")
      rig.line("2") // Add medication: the form step's first question is out
      assert(rig.lastChunks.exists(_.contains("question 1 of 4")), s"form question out: ${rig.lastChunks}")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1, "the stored form prompt is re-sent")
      assert(rig.lastChunks.exists(_.contains("question 1 of 4")), "the re-sent prompt is the form's")

      // The flow completes after the restart.
      rig.line("Vitamin D")
      rig.line("1000 IU")
      rig.line("09:00")
      rig.line("")
      rig.line("1")
      rig.line("1")
      assertEquals(rig.uow.medications.all.size, 1, "the wizard completed after the restart")
      assertEquals(rig.uow.sessions.all, Nil)

  test("the wizard survives a restart at the days step"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      assert(rig.lastChunks.exists(_.contains(WizardCopy.daysPrompt)), s"days prompt: ${rig.lastChunks}")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.lastChunks.exists(_.contains(WizardCopy.daysPrompt)), "the re-sent prompt is the days step's")

      rig.line("1") // Every day
      rig.line("1") // Create
      assertEquals(rig.uow.medications.all.size, 1)
      assertEquals(rig.uow.occurrences.all.size, 2)

  test("the wizard survives a restart at the days picker step"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("3") // Choose days...
      assert(rig.lastChunks.exists(_.contains("1) Mon")), s"the day picker: ${rig.lastChunks}")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.lastChunks.exists(_.contains("1) Mon")), "the re-sent prompt is the day picker's")

      rig.line("1 3 5")
      assert(rig.lastChunks.exists(_.contains("on Mon, Wed, Fri")), s"confirm card: ${rig.lastChunks}")
      rig.line("1") // Create
      assertEquals(rig.uow.medications.all.size, 1)
      val rule = rig.uow.revisions.all.head.rule.asInstanceOf[Rule.FixedTimes]
      assertEquals(
        rule.slotGroups.head.days,
        List(dosecord.contracts.Weekday.Mon, dosecord.contracts.Weekday.Wed, dosecord.contracts.Weekday.Fri)
      )

  test("the wizard survives a restart at the confirm step"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("1") // Every day
      assert(rig.lastChunks.exists(_.contains("every day 09:00 Europe/Kyiv")), s"confirm card: ${rig.lastChunks}")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.lastChunks.exists(_.contains("every day 09:00 Europe/Kyiv")), "the re-sent prompt is the card's")

      rig.line("1") // Create
      assertEquals(rig.uow.medications.all.size, 1)
      assertEquals(rig.uow.occurrences.all.size, 2)

  test("the wizard survives a restart at the timezone step"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("1") // Every day -> confirm
      rig.line("3") // Change timezone -> the tz picker
      assert(rig.lastChunks.exists(_.contains("Pick your timezone")), s"the tz picker: ${rig.lastChunks}")

      val restarted = rig.restartEngine()
      assertEquals(restarted.resumePending(), 1)
      assert(rig.lastChunks.exists(_.contains("Pick your timezone")), "the re-sent prompt is the tz picker's")

      rig.line("6") // America/New_York
      assert(rig.lastChunks.exists(_.contains("every day 09:00 America/New_York")), s"the card: ${rig.lastChunks}")
      rig.line("1") // Create
      assertEquals(rig.uow.medications.all.size, 1)
      assertEquals(rig.uow.schedules.all.head.tz.getId, "America/New_York")

  test("[Back] from step 2 returns to a pre-filled form"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("4") // Back (nav row)
      // The re-rendered form carries the previous answers: the RenderedForm (FormRunner rung) shows them as values
      // and the seeded run keeps them.
      val form = rig.lastForm.get
      assertEquals(
        form.fields.map(f => f.key -> f.value),
        List("name" -> Some("Vitamin D"), "dose" -> Some("1000 IU"), "times" -> Some("09:00"),
          "instructions" -> Some("with breakfast"))
      )
      assert(rig.lastChunks.exists(_.contains("[current: Vitamin D]")), s"the current value is shown: ${rig.lastChunks}")
      // Re-answering with the same values lands back on the days step with the data intact.
      rig.line("Vitamin D")
      rig.line("1000 IU")
      rig.line("09:00")
      rig.line("with breakfast")
      assert(rig.lastChunks.exists(_.contains(WizardCopy.daysPrompt)), s"back on the days step: ${rig.lastChunks}")

  test("[Back] from step 2 re-opens the pre-filled modal on the discord profile"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Discord)
      rig.bootstrapDiscord()
      rig.startWizardDiscord(instructions = "with breakfast")
      rig.tapLabel(WizardCopy.daysPrompt, Labels.Back)
      val modals = rig.openedForms
      assertEquals(modals.size, 2, "the modal re-opened")
      assertEquals(
        modals.last.fields.map(f => f.key -> f.value),
        List("name" -> Some("Vitamin D"), "dose" -> Some("1000 IU"), "times" -> Some("09:00"),
          "instructions" -> Some("with breakfast"))
      )

  test("/cancel mid-wizard leaves no medication row"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.line("Vitamin D")
      rig.line("1000 IU")
      val out = rig.line("/cancel")
      assert(out.exists(_.contains(WizardCopy.setupCancelled)), s"cancelled: $out")
      assertEquals(rig.uow.medications.all, Nil, "no medication row")
      assertEquals(rig.uow.schedules.all, Nil)
      assertEquals(rig.uow.occurrences.all, Nil)
      assertEquals(rig.uow.sessions.all, Nil, "the session is gone")

  test("a skipped instructions field stores NULL and the reminder has no second line"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Discord)
      rig.bootstrapDiscord()
      rig.startWizardDiscord(instructions = "")
      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.everyDay)
      rig.tapLabel("every day 09:00", Labels.Create)

      val medication = rig.uow.medications.all.head
      assertEquals(medication.instructions, None, "instructions stay NULL")
      val rendered = rig.renderReminderForNextOccurrence()
      assertEquals(rendered, List("Time for Vitamin D, 1000 IU."), s"one line only: $rendered")

  test("instructions appear on the reminder second line"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("1")
      rig.line("1")
      val rendered = rig.renderReminderForNextOccurrence()
      assertEquals(rendered, List("Time for Vitamin D, 1000 IU.", "with breakfast"), s"two lines: $rendered")

  test("find-or-create: re-adding a tracked name offers the update and replaces the schedule with a revision"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("1")
      rig.line("1")
      assertEquals(rig.uow.medications.all.size, 1)
      val scheduleId = rig.uow.schedules.all.head.id

      // Re-add the same name with a different plan (the submenu numbers shift once a medication exists, so tap by
      // label rather than by number).
      rig.tapLabel(MenuCopy.mainPrompt, "Medications")
      rig.tapLabel("Medications", "Add medication")
      rig.line("Vitamin D")
      rig.line("1000 IU")
      rig.line("21:00")
      rig.line("")
      rig.line("1") // Every day
      assert(
        rig.lastChunks.exists(_.contains("You already track Vitamin D")),
        s"the offer: ${rig.lastChunks}"
      )
      // The primary action is Update.
      val confirm = rig.lastOps.collect { case VendorOp.Send(_, m, _, _) => m }
        .find(_.chunks.exists(_.contains("You already track Vitamin D"))).get
      val updateWire = confirm.choiceMap.find(_.label == Labels.Update).map(_.callback)
      assert(updateWire.isDefined, "the confirm card offers Update")
      rig.tap(updateWire.get)
      assertEquals(rig.uow.medications.all.size, 1, "still one medication")
      val revisions = rig.uow.revisions.list(scheduleId)
      assertEquals(revisions.map(_.revision), List(1, 2), "the update is a new revision")
      assertEquals(
        revisions.last.rule,
        Rule.FixedTimes(List(SlotGroup(dosecord.contracts.Weekday.values.toList, List(dosecord.contracts.HhMm
          .unsafe("21:00")))))
      )

  test("as-needed: empty times skips the days step and creates an AsNeeded schedule"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Discord)
      rig.bootstrapDiscord()
      rig.startWizardDiscord(times = "", instructions = "")
      // No days step: the confirm card is the as-needed variant.
      assert(rig.lastChunks.exists(_.contains("as needed")), s"as-needed card: ${rig.lastChunks}")
      assert(rig.lastChunks.exists(_.contains(WizardCopy.asNeededNote)), s"the note: ${rig.lastChunks}")
      rig.tapLabel("as needed", Labels.Create)
      val schedule = rig.uow.schedules.all.head
      assertEquals(schedule.kind, "as_needed")
      assertEquals(rig.uow.occurrences.all, Nil, "as-needed materialises nothing")
      // The completion message repeats the as-needed note.
      assert(rig.lastChunks.exists(_.contains(WizardCopy.asNeededNote)), s"created note: ${rig.lastChunks}")

  test("the confirm card changes days and timezone"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("1") // Every day -> confirm
      rig.line("2") // Change days
      assert(rig.lastChunks.exists(_.contains(WizardCopy.daysPrompt)), s"back at days: ${rig.lastChunks}")
      rig.line("2") // Weekdays
      assert(rig.lastChunks.exists(_.contains("on weekdays 09:00 Europe/Kyiv")), s"weekdays card: ${rig.lastChunks}")
      rig.line("3") // Change timezone
      assert(rig.lastChunks.exists(_.contains("Pick your timezone")), s"tz picker: ${rig.lastChunks}")
      rig.line("6") // America/New_York
      assert(rig.lastChunks.exists(_.contains("on weekdays 09:00 America/New_York")), s"NY card: ${rig.lastChunks}")
      rig.line("1") // Create
      val schedule = rig.uow.schedules.all.head
      assertEquals(schedule.tz.getId, "America/New_York")
      assert(schedule.tzFollowsUser, "still follows the user zone for later account tz changes")
      val rule = rig.uow.revisions.all.head.rule.asInstanceOf[Rule.FixedTimes]
      assertEquals(rule.slotGroups.head.days, dosecord.contracts.Weekday.values.toList.take(5))

  test("the day picker accepts a native multi-select submission on the discord profile"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Discord)
      rig.bootstrapDiscord()
      rig.startWizardDiscord(instructions = "")
      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.chooseDays)
      // The native select submits every selected option's wire in `values`.
      val mon = rig.wireOf(WizardCopy.daysPrompt, "Mon")
      val wed = rig.wireOf(WizardCopy.daysPrompt, "Wed")
      val fri = rig.wireOf(WizardCopy.daysPrompt, "Fri")
      val payload = MedicationWizardRig.codec.decode(mon).toOption.get
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        "fake:msg:wiz:multiselect",
        MedicationWizardRig.t0,
        actor = MedicationWizardRig.actor,
        chat = ChatRef("fake", "dm:user-1"),
        body = Inbound.InteractionSubmitted(
          CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, mon),
          List(mon, wed, fri),
          None
        )
      )
      val before = rig.ops.size
      rig.push(event)
      rig.awaitOps(before)
      assert(rig.lastChunks.exists(_.contains("on Mon, Wed, Fri")), s"the card: ${rig.lastChunks}")
      rig.tapLabel("on Mon, Wed, Fri", Labels.Create)
      val rule = rig.uow.revisions.all.head.rule.asInstanceOf[Rule.FixedTimes]
      assertEquals(
        rule.slotGroups.head.days,
        List(dosecord.contracts.Weekday.Mon, dosecord.contracts.Weekday.Wed, dosecord.contracts.Weekday.Fri)
      )

  test("the day picker rejects junk and accepts names"):
    ox.supervised:
      val rig = MedicationWizardRig(Profile.Console)
      rig.bootstrapConsole()
      rig.startWizardConsole()
      rig.answerFormConsole()
      rig.line("3") // Choose days...
      val out = rig.line("8 9")
      assert(out.exists(_.contains(WizardCopy.invalidDays)), s"re-prompt: $out")
      rig.line("Mon, Wed")
      assert(rig.lastChunks.exists(_.contains("on Mon, Wed 09:00")), s"name selection: ${rig.lastChunks}")

/** Regenerates both transcript goldens:
  * `./mill core.test.runMain dosecord.core.application.medicationWizardGoldenGenerate`.
  */
@main def medicationWizardGoldenGenerate(): Unit =
  ox.supervised:
    Seq(Profile.Console -> MedicationWizardRig.ConsoleScript, Profile.Discord -> MedicationWizardRig.DiscordScript)
      .foreach: (profile, script) =>
        val transcript = MedicationWizardRig(profile).drive(script)
        val target = Path.of(MedicationWizardRig.goldenPath(profile))
        Option(target.getParent).foreach(Files.createDirectories(_))
        Files.writeString(target, transcript)
        println(s"wrote $target")
