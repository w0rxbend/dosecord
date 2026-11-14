package dosegram.core.application

import dosegram.contracts.VendorOp
import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.application.capture.CaptureParts
import dosegram.core.domain.copy.CommandCopy
import dosegram.core.domain.copy.HelpCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.Lang
import dosegram.core.domain.copy.LanguageCopy
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.StatusCards

/** ROADMAP G2.22, BOT_UX BUX-LANG-02: `/language` and the account menu's "Language" store the choice, the answer is in
  * the chosen language, and every later message follows the stored choice.
  */
class LanguageChatSuite extends munit.FunSuite:

  private def withRig(test: MedicationWizardRig => Unit): Unit =
    ox.supervised:
      val rig = MedicationWizardRig(
        Profile.Telegram,
        captureParts = Some(uow => clock => CaptureParts.over(uow, clock, SequentialIds(), StatusCards.textOnly)),
        drawing = CardDrawing.unavailable
      )
      test(rig)

  private def edited(ops: List[VendorOp]): List[String] =
    ops.flatMap:
      case VendorOp.Edit(_, message)       => message.chunks
      case VendorOp.Send(_, message, _, _) => message.chunks
      case _                               => Nil

  test("/language names each language in itself; a tap stores it and answers in the chosen language"):
    withRig: rig =>
      val account = rig.seedAccount()
      assertEquals(rig.command("language"), List(LanguageCopy.prompt))
      assertEquals(rig.lastChoiceMap(LanguageCopy.prompt).map(_.label), List("English", "Українська"))
      val answer = rig.tapOn(LanguageCopy.prompt, "Українська")
      assertEquals(edited(answer), List(LanguageCopy.changed(Lang.Uk)))
      assertEquals(rig.uow.accounts.settingsOf(account).flatMap(_.locale), Some("uk"))
      // Every later message follows the stored choice.
      assertEquals(rig.command("language"), List(Lang.within(Lang.Uk)(LanguageCopy.prompt)))
      val help = rig.command("help")
      assert(help.exists(_.contains(Lang.within(Lang.Uk)(HelpCopy.intro))), s"help: $help")
      val back = rig.tapOn(Lang.within(Lang.Uk)(LanguageCopy.prompt), "English")
      assertEquals(edited(back), List(LanguageCopy.changed(Lang.En)))
      assertEquals(rig.uow.accounts.settingsOf(account).flatMap(_.locale), Some("en"))

  test("the account menu offers Language next to Timezone"):
    withRig: rig =>
      rig.seedAccount()
      rig.command("menu")
      val account = rig.tapOn(MenuCopy.mainPrompt, MenuCopy.topLevel(5))
      assert(edited(account).contains(MenuCopy.accountPrompt), s"account menu: $account")
      val labels = account.flatMap:
        case VendorOp.Edit(_, message) => message.choiceMap.map(_.label)
        case _                         => Nil
      assertEquals(labels, List(Labels.Timezone, Labels.Language, Labels.Back))
      assertEquals(edited(rig.tapOn(MenuCopy.accountPrompt, Labels.Language)), List(LanguageCopy.prompt))

  test("every command of the product has a Ukrainian description within Telegram's limit (R-LOC-10)"):
    val specs = CommandRegistry.withCaptureByName.values.toList ++ CommandRegistry.all
    val missing = specs.map(_.name).distinct.filterNot(CommandCopy.ukrainianNames)
    assertEquals(missing, Nil)
    specs.foreach: spec =>
      val ukrainian = Lang.within(Lang.Uk)(CommandCopy.description(spec.name, spec.description))
      assert(ukrainian != spec.description && ukrainian.length <= 256, clue(spec.name))
end LanguageChatSuite
