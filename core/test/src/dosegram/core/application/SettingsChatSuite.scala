package dosegram.core.application

import dosegram.contracts.VendorOp
import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.application.capture.CaptureParts
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.SettingsCopy
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.StatusCards


/** The settings hub of `/settings` (supervisor decision, 2026-11-28; BOT_UX section 17) over the product composition:
  * one card with every setting beside its value, switches that edit the card in place, write through the Mini App's
  * writer with its audit row and change nothing on a repeated tap, and links to `/reminders` and `/privacy`.
  */
class SettingsChatSuite extends munit.FunSuite:

  private def withRig(test: MedicationWizardRig => Unit): Unit =
    ox.supervised:
      val rig = MedicationWizardRig(
        Profile.Telegram,
        captureParts = Some(uow => clock => CaptureParts.over(uow, clock, SequentialIds(), StatusCards.textOnly)),
        drawing = CardDrawing.unavailable
      )
      test(rig)

  /** The text of every message the ops send or edit. */
  private def texts(ops: List[VendorOp]): String =
    ops
      .flatMap:
        case VendorOp.Edit(_, message)       => message.chunks
        case VendorOp.Send(_, message, _, _) => message.chunks
        case _                               => Nil
      .mkString("\n")

  private def sends(ops: List[VendorOp]): Int = ops.count { case _: VendorOp.Send => true; case _ => false }
  private def edits(ops: List[VendorOp]): Int = ops.count { case _: VendorOp.Edit => true; case _ => false }

  private def settingsAudits(rig: MedicationWizardRig): List[List[String]] =
    rig.uow.auditLog.all.collect { case event: AuditEvent if event.objectType == "settings" => event.changedFields }

  private def hub(rig: MedicationWizardRig): String = rig.command("settings").mkString("\n")

  test("/settings shows every setting beside its value, in sections, with a switch for each one the chat sets"):
    withRig: rig =>
      rig.seedAccount(List(ConsentPurpose.Medication, ConsentPurpose.Mood))
      val card = hub(rig)
      List(
        SettingsCopy.title,
        SettingsCopy.regionSection,
        "Language: English",
        "Time zone: Europe/Kyiv, now ",
        SettingsCopy.daySection,
        "Wake 07:00 · Breakfast 08:00 · Lunch 13:00 · Dinner 19:00 · Bedtime 23:00",
        "Work: Mon to Fri 09:00–18:00",
        "Quiet hours: 22:00–07:00",
        SettingsCopy.imagesSection,
        "Image theme: Auto",
        "Discreet reminders: off",
        "Animated mood: off",
        "Counts in reports: on",
        "Notes after updates: on",
        SettingsCopy.reportsSection,
        "Morning summary: off · Weekly summary: off · Monthly summary: off",
        SettingsCopy.links
      ).foreach(line => assert(card.contains(line), s"'$line' in:\n$card"))
      assertEquals(
        rig.lastChoiceMap(SettingsCopy.title).map(_.label),
        List(
          "Українська",
          "Time zone",
          "Day and hours",
          "Theme: Auto",
          "Discreet: off",
          "Animated mood: off",
          "Counts: on",
          "Update notes: on",
          "Morning summary: off",
          "Reminders and reports",
          "Privacy"
        )
      )

  test("a switch edits the card in place with the old and the new value, with one audit row; a repeat writes nothing"):
    withRig: rig =>
      val account = rig.seedAccount()
      hub(rig)
      val (card, wire) = rig.controlOn(SettingsCopy.title, SettingsCopy.DiscreetButton(false))
      val answer = rig.tapOn(SettingsCopy.title, SettingsCopy.DiscreetButton(false))
      assertEquals((sends(answer), edits(answer)), (0, 1), "the card is edited, nothing is sent")
      val edited = texts(answer)
      assert(edited.contains(SettingsCopy.changed(SettingsCopy.discreetName, "off", "on")), edited)
      assert(edited.contains("Discreet reminders: on"), edited)
      assert(rig.uow.accounts.settingsOf(account).exists(_.isDiscreet))
      assertEquals(settingsAudits(rig), List(List("discreet")))
      // The same button again, as a second tap of the old card: the state it sets is already stored.
      val (_, repeat) = rig.tapFrom(card, wire)
      assertEquals(sends(repeat), 0)
      assert(!texts(repeat).contains("Saved."), texts(repeat))
      assert(rig.uow.accounts.settingsOf(account).exists(_.isDiscreet))
      assertEquals(settingsAudits(rig), List(List("discreet")))
      rig.tapOn(SettingsCopy.title, SettingsCopy.DiscreetButton(true))
      assert(rig.uow.accounts.settingsOf(account).exists(!_.isDiscreet))
      assertEquals(settingsAudits(rig), List(List("discreet"), List("discreet")))

  test("the image theme goes from Auto to Light to Dark and back, the value the Mini App reads"):
    withRig: rig =>
      val account = rig.seedAccount()
      hub(rig)
      def theme = rig.uow.accounts.settingsOf(account).map(_.imageTheme)
      rig.tapOn(SettingsCopy.title, SettingsCopy.ThemeButton(ImageTheme.Auto))
      assertEquals(theme, Some(ImageTheme.Light))
      rig.tapOn(SettingsCopy.title, SettingsCopy.ThemeButton(ImageTheme.Light))
      assertEquals(theme, Some(ImageTheme.Dark))
      val last = rig.tapOn(SettingsCopy.title, SettingsCopy.ThemeButton(ImageTheme.Dark))
      assertEquals(theme, Some(ImageTheme.Auto))
      assert(texts(last).contains(SettingsCopy.changed(SettingsCopy.themeName, "Dark", "Auto")), texts(last))
      assertEquals(settingsAudits(rig), List.fill(3)(List("image_theme")))

  test("counts in reports and the notes after updates switch off and on, each audited"):
    withRig: rig =>
      val account = rig.seedAccount()
      hub(rig)
      rig.tapOn(SettingsCopy.title, SettingsCopy.CountsButton(true))
      assert(!rig.uow.accounts.gamificationOf(account))
      val notes = rig.tapOn(SettingsCopy.title, SettingsCopy.NotesButton(true))
      assert(!rig.uow.accounts.whatsNewOf(account).enabled)
      assert(texts(notes).contains("Counts in reports: off"), texts(notes))
      assert(texts(notes).contains("Notes after updates: off"), texts(notes))
      rig.tapOn(SettingsCopy.title, SettingsCopy.CountsButton(false))
      assert(rig.uow.accounts.gamificationOf(account))
      assertEquals(settingsAudits(rig), List(List("counts"), List("whats_new"), List("counts")))

  test("the language switch stores the choice and writes the card in the chosen language at once"):
    withRig: rig =>
      val account = rig.seedAccount()
      hub(rig)
      val answer = texts(rig.tapOn(SettingsCopy.title, "Українська"))
      assertEquals(rig.uow.accounts.settingsOf(account).flatMap(_.locale), Some("uk"))
      Lang.within(Lang.Uk):
        assert(answer.contains(SettingsCopy.title), answer)
        assert(answer.contains(SettingsCopy.changed(SettingsCopy.languageName, "English", "Українська")), answer)
      assertEquals(settingsAudits(rig), List(List("locale")))
      // The edited card offers the way back, named in its own language.
      rig.controlOn(Lang.within(Lang.Uk)(SettingsCopy.regionSection), "English"): Unit

  test("animated mood is offered with the mood consent only, and a tap without it changes nothing"):
    withRig: rig =>
      val account = rig.seedAccount()
      val card = hub(rig)
      assert(!card.contains(SettingsCopy.animatedName), card)
      val wire = MedicationWizardRig.codec
        .encode(CallbackMode.Direct, ActionRegistry.byName("settings.animated").get, account, 1)
        .wire
      rig.tap(wire)
      assert(rig.uow.accounts.settingsOf(account).exists(!_.animatedMood))
      val t0 = MedicationWizardRig.t0
      rig.uow.consents.grant(account, ConsentPurpose.Mood, ConsentNotice(1, "seeded for the test", "en"), ConsentTaps(t0, t0))
      hub(rig)
      rig.tapOn(SettingsCopy.title, SettingsCopy.AnimatedButton(false))
      assert(rig.uow.accounts.settingsOf(account).exists(_.animatedMood))
      assertEquals(settingsAudits(rig), List(List("animated_mood")))

  test("Reminders and reports opens the /reminders hub, and Privacy the /privacy card, in the card's place"):
    withRig: rig =>
      rig.seedAccount()
      hub(rig)
      val reminders = rig.tapOn(SettingsCopy.title, SettingsCopy.RemindersButton)
      assertEquals(sends(reminders), 0)
      assert(texts(reminders).contains(PromptCopy.hubTitle), texts(reminders))
      hub(rig)
      val privacy = rig.tapOn(SettingsCopy.title, SettingsCopy.PrivacyButton)
      assertEquals(sends(privacy), 0)
      assert(texts(privacy).contains(ConsentCopy.privacyTitle), texts(privacy))

  test("Time zone starts the time zone flow"):
    withRig: rig =>
      rig.seedAccount()
      hub(rig)
      val flow = texts(rig.tapOn(SettingsCopy.title, SettingsCopy.ZoneButton))
      assert(
        flow.contains(IdentityCopy.timezonePrompt) || flow.contains(IdentityCopy.timezoneSuggested),
        s"the zone picker: $flow"
      )

  test("/settings is listed, in /help, and reached from the account menu"):
    withRig: rig =>
      rig.seedAccount()
      assert(CommandRegistry.withCapture.contains(CommandRegistry.Settings))
      assert(rig.command("help").exists(_.contains("/settings")))
      rig.command("menu")
      rig.tapOn(MenuCopy.mainPrompt, MenuCopy.topLevel(5))
      val opened = texts(rig.tapOn(MenuCopy.accountPrompt, Labels.Settings))
      assert(opened.contains(SettingsCopy.regionSection), opened)

  test("/settings before an account shows no settings and writes nothing"):
    withRig: rig =>
      assert(!rig.command("settings").exists(_.contains(SettingsCopy.regionSection)))
      assertEquals(settingsAudits(rig), Nil)
end SettingsChatSuite
