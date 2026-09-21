package dosecord.core.chat

import dosecord.contracts.CallbackRef
import dosecord.contracts.Inbound
import dosecord.contracts.WizardDocument
import dosecord.core.domain.copy.WizardCopy

import java.util.UUID

// Acceptance 5 (unit half): a completed FormRunner emits exactly one FormSubmitted, equal to the modal path's
// (same form id, same submit callback, same fields) — the core never learns which happened (DESIGN.md section 4.3).
class FormRunnerSuite extends munit.FunSuite:

  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val runner = FormRunner(codec)
  private val sessionId = UUID.randomUUID()
  private val fields = WizardTestFlows.formFields

  private def refOf(wire: String): CallbackRef =
    val payload = codec.decode(wire).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire)

  test("a completed run yields one FormSubmitted equal to the modal path's"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val submitWire =
      codec.encode(CallbackMode.Slot, ActionRegistry.byName("wizard.confirm").get, UUID.randomUUID(), 0).wire

    // The modal path: the vendor delivers one FormSubmitted carrying the submit token and every field at once.
    val modal = Inbound.FormSubmitted("details-form", refOf(submitWire), Map("a" -> "1", "b" -> "2"))

    val outcomes = uow.transaction { tx =>
      runner.start(tx, sessionId, "details-form", submitWire)
      val first = runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "1")
      val second = runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "2")
      List(first, second)
    }

    assertEquals(outcomes.head, FormRunner.AnswerOutcome.Advanced(1))
    val completed = outcomes(1) match
      case FormRunner.AnswerOutcome.Completed(submitted) => submitted
      case other => fail(s"expected completion, got $other")
    assertEquals(completed, modal, "the FormRunner's FormSubmitted equals the modal path's")
    assertEquals(uow.formRuns.all, Nil, "the run is deleted on completion")

  test("a blank answer to a required field is re-asked; an optional field accepts blank"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val submitWire =
      codec.encode(CallbackMode.Slot, ActionRegistry.byName("wizard.confirm").get, UUID.randomUUID(), 0).wire

    val outcomes = uow.transaction { tx =>
      runner.start(tx, sessionId, "details-form", submitWire)
      val blank = runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "   ")
      assertEquals(blank, FormRunner.AnswerOutcome.Invalid(WizardCopy.fieldRequired))
      val first = runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "1")
      val second = runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "")
      (blank, first, second)
    }

    assertEquals(outcomes._2, FormRunner.AnswerOutcome.Advanced(1))
    outcomes._3 match
      case FormRunner.AnswerOutcome.Completed(submitted) =>
        assertEquals(submitted.fields, Map("a" -> "1", "b" -> ""))
      case other => fail(s"expected completion, got $other")

  test("partial answers survive in form_runs (the restart story)"):
    val uow = MediatorFakes.InMemoryUnitOfWork()
    val submitWire =
      codec.encode(CallbackMode.Slot, ActionRegistry.byName("wizard.confirm").get, UUID.randomUUID(), 0).wire

    uow.transaction { tx =>
      runner.start(tx, sessionId, "details-form", submitWire)
      runner.accept(tx, tx.formRuns.loadForUpdate(sessionId).get, fields, "1")
    }
    val stored = uow.formRuns.all.head
    assertEquals(stored.fieldIndex, 1)
    assertEquals(stored.formId, "details-form")
    assert(WizardDocument.answersFromJson(stored.answers).get("a").contains("1"))
