package dosegram.core.application

import dosegram.core.application.MedicationWizardRig.Profile
import dosegram.core.domain.copy.InputCopy
import dosegram.core.domain.copy.MenuCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.input.TimeOfDay

import java.nio.file.Files
import java.nio.file.Path
import scala.io.Source
import scala.util.Using

/** The owner's report on the live bot (2026-11-13): "9:00" at the times question failed without saying why. Before
  * the fix the form took "9:00", asked the fourth question, and only then answered "Times must be HH:MM,
  * comma-separated" and restarted the whole form at the name, because `HhMm` wanted two hour digits and the form
  * checked its answers only at the end. These transcripts pin the times step that replaced it (BOT_UX section 15.6).
  */
class TimeInputTranscriptSuite extends munit.FunSuite:
  import TimeInputTranscriptSuite.*

  test("transcripts (golden, telegram): the owner's case, an error, two failures, several times"):
    ox.supervised:
      assertEquals(
        transcript(),
        expected,
        "transcript drifted; regenerate with ./mill core.test.runMain dosegram.core.application.timeInputGoldenGenerate"
      )

  test("the owner's case: 9:00 is understood as 09:00 and echoed with a way to change it"):
    ox.supervised:
      val rig = atTimesStep()
      val out = rig.line("9:00")
      assert(out.exists(_.contains(WizardCopy.timesEcho(List("09:00")))), s"echo: $out")
      assert(rig.lastChoiceMap(WizardCopy.instructionsPrompt).exists(_.label == WizardCopy.changeTimes), "edit path")

  test("a time that is not understood is named, with examples, and the times step stays open"):
    ox.supervised:
      val rig = atTimesStep()
      val out = rig.line("9:75")
      assert(out.exists(_.contains(InputCopy.time(TimeOfDay.Rejection.NotATime("9:75")))), s"explained: $out")
      assert(out.exists(_.contains(WizardCopy.timesPrompt)), s"same step: $out")
      assert(rig.line("9am").exists(_.contains(WizardCopy.timesEcho(List("09:00")))))

  test("a typed number at the times step is a time, never the number of a button"):
    ox.supervised:
      val rig = atTimesStep()
      // 9 buttons are on the prompt (5 times, Other time, As needed, Back, Cancel): "9" must not tap Cancel.
      val out = rig.line("9")
      assert(out.exists(_.contains(WizardCopy.timesEcho(List("09:00")))), s"read as a time: $out")
      assertEquals(rig.uow.sessions.all.size, 1, "the wizard is still open")

  test("two failures at Other time bring back the buttons of common times"):
    ox.supervised:
      val rig = atTimesStep()
      rig.tapLabel(WizardCopy.timesPrompt, WizardCopy.otherTime)
      val first = rig.line("lunch")
      assert(first.exists(_.contains(WizardCopy.timesOtherPrompt)), s"first failure stays: $first")
      val second = rig.line("after lunch")
      assert(second.exists(_.contains(WizardCopy.timesPrompt)), s"second failure offers buttons: $second")
      assert(rig.lastChoiceMap(WizardCopy.timesPrompt).exists(_.label == "09:00"), "the quick times are back")

  test("several times in one message"):
    ox.supervised:
      val rig = atTimesStep()
      val out = rig.line("8, 14:30 and 22")
      assert(out.exists(_.contains(WizardCopy.timesEcho(List("08:00", "14:30", "22:00")))), s"echo: $out")
      rig.line("")
      rig.tapLabel(WizardCopy.daysPrompt, WizardCopy.everyDay)
      assert(rig.lastChunks.exists(_.contains("every day 08:00, 14:30, 22:00")), s"card: ${rig.lastChunks}")

object TimeInputTranscriptSuite:

  val goldenPath = "core/test/resources/goldens/transcripts/time-input.telegram.golden"

  /** A Telegram account at the dose question of the add-medication wizard, the name answered. */
  def atDoseQuestion()(using ox.Ox): MedicationWizardRig =
    val rig = MedicationWizardRig(Profile.Telegram)
    rig.bootstrapTelegram()
    rig.command("menu")
    rig.tapLabel(MenuCopy.mainPrompt, "Medications")
    rig.tapLabel("Medications", "Add medication")
    rig.line("Vitamin D")
    rig

  /** The same account at the times step, the dose answered. */
  def atTimesStep()(using ox.Ox): MedicationWizardRig =
    val rig = atDoseQuestion()
    rig.line("1000 IU")
    rig

  /** Every scenario starts at the dose answer, so the times prompt and its buttons are in the transcript. */
  private val scenarios: List[(String, List[MedicationWizardRig => List[String]])] = List(
    "the owner's case" -> List(_.typeT("1000 IU"), _.typeT("9:00")),
    "an error, then the explanation" -> List(_.typeT("1000 IU"), _.typeT("9:75"), _.typeT("9am")),
    "two failures, then buttons" -> List(
      _.typeT("1000 IU"),
      _.tapT(WizardCopy.timesPrompt, WizardCopy.otherTime),
      _.typeT("lunch"),
      _.typeT("after lunch"),
      _.tapT(WizardCopy.timesPrompt, "08:00")
    ),
    "several times in one message" -> List(
      _.typeT("1000 IU"),
      _.typeT("8, 14:30 and 22"),
      _.typeT("with breakfast"),
      _.tapT(WizardCopy.daysPrompt, WizardCopy.everyDay)
    )
  )

  def transcript()(using ox.Ox): String =
    scenarios
      .map((name, script) => s"## $name\n" + atDoseQuestion().drive(script))
      .mkString("\n")

  def expected: String =
    val stream = Option(getClass.getResourceAsStream("/goldens/transcripts/time-input.telegram.golden"))
      .getOrElse(throw IllegalStateException("the time-input golden is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

/** Regenerates the golden: `./mill core.test.runMain dosegram.core.application.timeInputGoldenGenerate`. */
@main def timeInputGoldenGenerate(): Unit =
  ox.supervised:
    val target = Path.of(TimeInputTranscriptSuite.goldenPath)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, TimeInputTranscriptSuite.transcript())
    println(s"wrote $target")
