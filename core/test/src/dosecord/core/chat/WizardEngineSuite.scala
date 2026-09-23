package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.domain.copy.WizardCopy

import java.util.UUID

/** M0.12b engine acceptance over the in-memory fakes (the Postgres half is `WizardEnginePgSuite`): routing, the global
  * interrupts, the confirm-cancel prompt, the direct-token bypass, step rendering and the FormRunner path.
  */
class WizardEngineSuite extends munit.FunSuite:

  import WizardTestFlows.*

  test("happy path: text step, choice step, confirm; session steps and closes"):
    val h = WizardHarness()

    val start = h.command("example")
    assert(h.bodyText(start).contains("What should I call it?"), "first prompt")
    assertEquals(h.session.step, "name")
    assertEquals(h.session.stepSeq, 0)

    val named = h.message("Widget")
    assert(h.bodyText(named).contains("Pick a color for Widget."), "step 2 prompt echoes the saved answer")
    assertEquals(h.session.step, "color")
    assertEquals(h.session.stepSeq, 1)

    val colored = h.tap(h.choiceWire(named, "Red"))
    assertEquals(h.session.step, "confirm")
    assertEquals(h.session.stepSeq, 2)

    val done = h.tap(h.choiceWire(colored, Labels.Create))
    assert(h.bodyText(done).contains("Created Widget (red)."), s"completion reply: ${h.bodyText(done)}")
    assertEquals(h.uow.sessions.all, Nil, "session closes on Complete")
    assertEquals(h.uow.slots.all, Nil, "slots are deleted on Complete")

  // Acceptance 8 half: Back/Cancel behave on every step (rendering is asserted separately below).
  test("Back returns to the previous step with the data kept; Cancel aborts the setup"):
    val h = WizardHarness()
    h.command("example")
    val named = h.message("Widget")
    assertEquals(h.session.step, "color")

    val back = h.tap(h.choiceWire(named, Labels.Back))
    assertEquals(h.session.step, "name", "Back pops to the previous step")
    assert(h.bodyText(back).contains("What should I call it?"), "the previous step re-renders")
    assertEquals(WizardEngine.dataOf(h.session).get("name"), Some("Widget"), "answers are kept")

    val named2 = h.message("Gadget")
    val cancelled = h.tap(h.choiceWire(named2, Labels.Cancel))
    assert(h.bodyText(cancelled).contains(WizardCopy.setupCancelled), s"abort reply: ${h.bodyText(cancelled)}")
    assertEquals(h.uow.sessions.all, Nil, "session is deleted on Cancel")
    assertEquals(h.uow.slots.all, Nil, "callback slots are deleted on Cancel")

  // Acceptance 2 (unit half): a stale step_seq tap gets the toast and the current prompt.
  test("a stale step_seq tap gets the toast and the current prompt re-sent"):
    val h = WizardHarness()
    h.command("example")
    val named = h.message("Widget") // color step at step_seq 1
    val staleCancel = h.choiceWire(named, Labels.Cancel)
    h.tap(h.choiceWire(named, "Red")) // advances to step_seq 2

    val reply = h.tap(staleCancel)
    assertEquals(reply.toast, Some(ReminderCopy.staleControlToast))
    assertEquals(
      reply.followUps.map(OutboundMessage.toJson),
      List(h.session.lastPrompt.get),
      "the stored current prompt is re-sent"
    )
    assertEquals(h.session.step, "confirm", "the session is untouched by the stale tap")

  // Acceptance 6 (unit half): a direct dose token mid-wizard is handled and the wizard is untouched.
  test("a direct dose token mid-wizard reaches the inner handler and the session is untouched"):
    val h = WizardHarness()
    h.command("example")
    val before = h.session

    val direct = h.codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, UUID.randomUUID(), 0)
    h.tap(direct.wire)

    assertEquals(h.inner.handled, 1, "the direct token reached the inner handler")
    assertEquals(h.session, before, "the wizard session was not touched")

  // Acceptance 7: /cancel and /menu interrupt; a new flow mid-session asks "Cancel the current setup?".
  test("/cancel aborts the active setup; with none it says there is nothing to cancel"):
    val h = WizardHarness()
    h.command("example")
    val cancelled = h.command("cancel")
    assert(h.bodyText(cancelled).contains(WizardCopy.setupCancelled))
    assertEquals(h.uow.sessions.all, Nil)

    val idle = h.command("cancel")
    assert(h.bodyText(idle).contains(WizardCopy.nothingToCancel))

  test("/menu interrupts the active setup and delegates to the menu handler"):
    val h = WizardHarness()
    h.command("example")
    h.message("Widget")

    h.command("menu")
    assertEquals(h.uow.sessions.all, Nil, "the setup is interrupted")
    assertEquals(h.uow.slots.all, Nil, "its slots are deleted")
    assertEquals(h.inner.handled, 1, "the menu command reached the inner handler")

  test("a new flow mid-session asks 'Cancel the current setup?'; Yes replaces, No keeps"):
    val h = WizardHarness()
    h.command("example")
    h.message("Widget")

    val confirm = h.command("form")
    assert(h.bodyText(confirm).contains(WizardCopy.cancelCurrentSetup), s"confirm prompt: ${h.bodyText(confirm)}")

    // No: the old session resumes at its current step.
    val kept = h.tap(h.choiceWire(confirm, Labels.No))
    assertEquals(h.session.flow, "example")
    assertEquals(h.session.step, "color")
    assert(h.bodyText(kept).contains("Pick a color for Widget."), "the current prompt is re-rendered")

    // Yes: the old session is replaced by the new flow.
    val confirm2 = h.command("form")
    h.tap(h.choiceWire(confirm2, Labels.Yes))
    assertEquals(h.session.flow, "formflow")
    assertEquals(h.session.step, "details")
    assertEquals(h.session.stepSeq, 0, "the new flow starts at its first step")

  // Acceptance 8: [Back]/[Cancel] on every step — as a nav row on choice steps, as a typed hint on text/form steps
  // (so a bare digit is never eaten while the wizard waits for free text, DESIGN.md section 4.6 step 5).
  test("every step renders with Back and Cancel"):
    val h = WizardHarness()
    flows.values.flatMap(_.steps.values).foreach: step =>
      val prompt = h.uow.transaction(tx =>
        h.engine.renderStepPrompt(
          h.chat,
          None,
          UUID.randomUUID(),
          step,
          Map("name" -> "Widget", "color" -> "red", "a" -> "1", "b" -> "2"),
          0,
          tx
        )
      )
      step.kind match
        case StepKind.Choices(_, _, _, _) =>
          val labels = prompt.blocks
            .collect { case Block.Choices(cs) => cs.choices.map(_.label) }
            .flatten
          assert(labels.contains(Labels.Back), s"step '${step.id}' has [Back]")
          assert(labels.contains(Labels.Cancel), s"step '${step.id}' has [Cancel]")
        case StepKind.Text =>
          assert(
            h.bodyText(Reply(replace = Some(prompt))).contains(WizardCopy.textNavHint),
            s"text step '${step.id}' carries the typed Back/Cancel hint"
          )
        case StepKind.Form(_, _, _) =>
          assert(prompt.blocks.exists(_.isInstanceOf[Block.FormBlock]), s"form step '${step.id}' renders its form")
          assert(
            h.bodyText(Reply(replace = Some(prompt))).contains(WizardCopy.textNavHint),
            s"form step '${step.id}' carries the typed Back/Cancel hint"
          )

  // Acceptance 5 (engine half): the FormRunner path and the modal path drive the step to the same outcome.
  test("FormRunner text answers and a native modal submit yield the same step outcome"):
    def run(flow: WizardHarness => (String, Map[String, String])): (String, Map[String, String]) =
      val h = WizardHarness()
      h.command("form")
      flow(h)

    // Modal path: one FormSubmitted with the prompt's submit token.
    val viaModal = run { h =>
      val start = OutboundMessage.fromJson(h.session.lastPrompt.get)
      val form = h.formBlock(Reply(replace = Some(start)))
      h.submitForm(form.id, form.submit, Map("a" -> "1", "b" -> "2"))
      (h.session.step, WizardEngine.dataOf(h.session))
    }

    // FormRunner path: the same answers as plain text messages.
    val viaRunner = run { h =>
      h.message("1")
      h.message("2")
      (h.session.step, WizardEngine.dataOf(h.session))
    }

    assertEquals(viaRunner, viaModal, "the FormRunner outcome equals the modal path's")

  test("FormRunner asks one field per message and re-asks a blank required field"):
    val h = WizardHarness()
    h.command("form")
    assertEquals(h.uow.formRuns.all.size, 1, "a form run starts with the prompt")

    val q1 = h.message("")
    assert(h.bodyText(q1).contains(WizardCopy.fieldRequired), s"blank required answer: ${h.bodyText(q1)}")
    assert(h.bodyText(q1).contains("question 1 of 2"), "the same field is re-asked")

    val q2 = h.message("1")
    assert(h.bodyText(q2).contains("question 2 of 2"), s"next question: ${h.bodyText(q2)}")
    assertEquals(h.uow.formRuns.all.head.fieldIndex, 1)

    h.message("2")
    assertEquals(h.uow.formRuns.all, Nil, "the run is deleted on completion")
    assertEquals(h.session.step, "done")
    assertEquals(WizardEngine.dataOf(h.session), Map("a" -> "1", "b" -> "2"))

  // Acceptance 1 (unit half): the stored last_prompt re-sends on restart, and the session resumes the same step.
  test("restart resume re-sends the stored prompt of every live session"):
    val h = WizardHarness()
    h.command("example")
    h.message("Widget")
    val storedPrompt = OutboundMessage.fromJson(h.session.lastPrompt.get)
    val sendsBefore = h.adapter.ops.size

    // A new engine over the same state, as after a process restart.
    val restarted = WizardEngine(flows, flowCommands, h.codec, h.clock, h.inner, h.uow, Map(h.adapter.vendor -> h.adapter))
    val resent = restarted.resumePending()

    assertEquals(resent, 1)
    val sends = h.adapter.ops.drop(sendsBefore).collect { case s: VendorOp.Send => s }
    assertEquals(sends.size, 1, "exactly one prompt re-sent")
    val (expectedOps, _) =
      Renderer.render(storedPrompt, h.chat, h.adapter.capabilities, storedPrompt.dedupeKey, RenderContext())
    val expected = expectedOps.collectFirst { case s: VendorOp.Send => s.message.chunks }.get
    assertEquals(sends.head.message.chunks, expected)
    assertEquals(h.session.step, "color", "the session resumes the same step")

    // The flow continues from the re-sent prompt: its slots survived the restart.
    val colored = h.tap(h.choiceWire(Reply(replace = Some(storedPrompt)), "Red"))
    assertEquals(h.session.step, "confirm")
    assert(h.bodyText(colored).contains("create it?"))
