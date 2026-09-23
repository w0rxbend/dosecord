package dosecord.core.chat

import java.util.UUID

import dosecord.contracts.*
import dosecord.core.domain.copy.DigestCopy
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.domain.copy.WizardCopy

/** The suite B1 fixture set (ROADMAP M0.9): one golden per scenario per profile. Callback tokens are minted with the
  * real CallbackCodec under a fixed test key, so goldens show the true 49-char wire form. All user-facing copy comes
  * from the M1.4a catalogue (`core/domain/copy`), so the goldens pin the catalogue's rendered form, not placeholders.
  */
object GoldenScenarios:

  private val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill(32)(7.toByte)), None))
  private val occurrence = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")
  private val sessionSubject = UUID.fromString("ff112233-4455-6677-8899-aabbccddeeff")

  private def token(action: String, subject: UUID, value: Long): CallbackToken =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value)

  private def doseToken(action: String, value: Long = 0): CallbackToken =
    token(action, occurrence, value)

  private def text(s: String): RichText = List(Node.Paragraph(List(Inline.Text(s))))

  private def lines(ls: List[String]): RichText = ls.map(l => Node.Paragraph(List(Inline.Text(l))))

  private def base(body: RichText, blocks: List[Block], importance: Importance = Importance.Info): OutboundMessage =
    OutboundMessage(
      body = body,
      blocks = blocks,
      importance = importance,
      dedupeKey = "dedupe:test",
      correlationId = "corr:test"
    )

  private def chat(profile: String): ChatRef = ChatRef(profile, "dm:owner")

  private def reminderMessage: OutboundMessage =
    base(
      lines(ReminderCopy.reminderBody("Vitamin D", Some("1000 IU"), None)),
      Controls.reminder(
        taken = doseToken("dose.taken"),
        skip = doseToken("dose.skip"),
        snooze = List(
          10 -> doseToken("dose.snooze", 10),
          30 -> doseToken("dose.snooze", 30),
          60 -> doseToken("dose.snooze", 60)
        )
      ),
      Importance.Reminder
    )

  private def menuMessage: OutboundMessage =
    base(
      text(MenuCopy.mainPrompt),
      List(
        Block.Choices(
          ChoiceSet(
            id = "menu.main",
            choices = MenuCopy.topLevel.zipWithIndex.map { (label, i) =>
              Choice(label, token("menu.open", sessionSubject, i + 1).wire)
            }
          )
        )
      )
    )

  private def daysMessage: OutboundMessage =
    base(
      text(WizardCopy.scheduleSetupIntro),
      List(
        Block.Choices(
          ChoiceSet(
            id = "wizard.days",
            prompt = Some(text(WizardCopy.daysPrompt)),
            choices = WizardCopy.weekdayLabels.zipWithIndex.map { (label, i) =>
              Choice(label, token("wizard.step", sessionSubject, i + 1).wire)
            },
            layout = ChoiceLayout.Select,
            minSelect = 1,
            maxSelect = 7
          )
        )
      )
    )

  private def formMessage: OutboundMessage =
    base(
      text(WizardCopy.addMedicationIntro),
      List(
        Block.FormBlock(
          Form(
            id = "medication.add",
            title = WizardCopy.addMedicationTitle,
            // The M1.9 wizard's real step 1 form (times optional: empty = as-needed).
            fields = dosecord.core.application.medication.AddMedicationFlow.formFields,
            submit = token("wizard.text_step", sessionSubject, 1).wire
          )
        )
      )
    )

  private val fillerUnit = "the quick brown fox jumps over the lazy dog "

  private def filler(chars: Int): String =
    (fillerUnit * (chars / fillerUnit.length + 1)).take(chars)

  // Exactly 5 000 chars when joined with blank lines: 9 x 489 + 581 + 9 x 2 separators.
  private def longBodyMessage: OutboundMessage =
    val paragraphs = List.fill(9)(filler(489)) :+ filler(581)
    base(
      paragraphs.map(p => Node.Paragraph(List(Inline.Text(p)))),
      List(
        Block.Choices(
          ChoiceSet(id = "ack.ok", choices = List(Choice("OK", token("menu.open", sessionSubject, 99).wire)))
        )
      )
    )

  private def digestMessage: OutboundMessage =
    val items = (1 to 9).toList.map { i =>
      val itemSubject = UUID.fromString(f"00000000-0000-0000-0000-${i}%012d")
      Block.Choices(
        ChoiceSet(
          id = s"digest.item$i",
          prompt = Some(text(s"Dose $i")),
          choices = List(
            Choice(Labels.Taken, token("dose.taken", itemSubject, 0).wire, ChoiceStyle.Success),
            Choice(Labels.snooze(10), token("dose.snooze", itemSubject, 10).wire),
            Choice(Labels.Skip, token("dose.skip", itemSubject, 0).wire)
          )
        )
      )
    }
    base(text(DigestCopy.header(9)), items, Importance.Bulk)

  private def discreetMessage: OutboundMessage =
    reminderMessage.copy(body = text(ReminderCopy.discreetReminderBody("09:00")), discreet = true)

  private def postTakenOps(profile: String, p: CapabilityProfile): (List[VendorOp], RenderReport) =
    Renderer.renderFinalize(
      handle = MessageHandle(profile, "dm:owner", "m42"),
      summary = text(ReminderCopy.takenConfirmation("09:03")),
      keep = Some(Controls.postTaken(doseToken("dose.undo"), doseToken("dose.correct"))),
      chat = chat(profile),
      profile = p,
      sendKey = "post_taken:1"
    )

  /** Every (scenario, profile) cell of suite B1 and how it renders. */
  def renderAll: Map[(String, String), (List[VendorOp], RenderReport)] =
    CapabilityProfiles.all.flatMap { (profileName, profile) =>
      scenarios(profileName).map { (scenario, render) =>
        (scenario, profileName) -> render(profile)
      }
    }.toMap

  private def scenarios(profileName: String): List[(String, CapabilityProfile => (List[VendorOp], RenderReport))] =
    List(
      "reminder" -> ((p: CapabilityProfile) => Renderer.render(reminderMessage, chat(profileName), p, "reminder:1")),
      "menu" -> ((p: CapabilityProfile) => Renderer.render(menuMessage, chat(profileName), p, "menu:1")),
      "days-multi-select" -> ((p: CapabilityProfile) => Renderer.render(daysMessage, chat(profileName), p, "days:1")),
      "post-taken" -> ((p: CapabilityProfile) => postTakenOps(profileName, p)),
      "form" -> ((p: CapabilityProfile) =>
        Renderer.render(formMessage, chat(profileName), p, "form:1", RenderContext(liveInteraction = true))
      ),
      "long-body" -> ((p: CapabilityProfile) => Renderer.render(longBodyMessage, chat(profileName), p, "long:1")),
      "digest" -> ((p: CapabilityProfile) => Renderer.render(digestMessage, chat(profileName), p, "digest:1")),
      "discreet-reminder" -> ((p: CapabilityProfile) =>
        Renderer.render(discreetMessage, chat(profileName), p, "discreet:1")
      )
    )

  /** The discreet scenario must never leak a medication name (acceptance 6). */
  val MedicationNames: List[String] = List("Vitamin D")
end GoldenScenarios
