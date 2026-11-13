package dosegram.core.chat

import dosegram.contracts.*
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.ports.*

import java.sql.SQLException
import java.time.Duration
import java.util.UUID

object WizardEngine:
  /** Sessions go idle after 30 min without a step (DESIGN.md section 4.6). */
  val IdleAfter: Duration = Duration.ofMinutes(30)

  /** The "Still there?" grace window; the sweeper aborts the session when it lapses. */
  val GracePeriod: Duration = Duration.ofMinutes(30)

  // Reserved session-data keys (flow answers use plain keys).
  private[chat] val HistoryKey = "_history"
  private[chat] val GraceKey = "_grace"

  /** The option keys currently on in a toggle keyboard, comma-joined; reset whenever the step changes. */
  private[chat] val SelectedKey = "_selected"

  /** Rejected inputs in a row at the current step; dropped whenever the step changes. */
  private[chat] val FailuresKey = "_failures"

  // Slot payload keys the engine dispatch matches on; everything else is a step choice key.
  private[chat] val BackKey = "engine.back"
  private[chat] val CancelKey = "engine.cancel"
  private[chat] val FormSubmitKey = "engine.form"
  private[chat] val GraceContinueKey = "engine.grace.continue"
  private[chat] val GraceCancelKey = "engine.grace.cancel"
  private[chat] val ConfirmCancelPrefix = "engine.confirmcancel:"
  private[chat] val ConfirmKeepKey = "engine.confirmcancel.keep"
  private[chat] val DoneKey = "engine.done"

  private[chat] val SelectedMark = "✓ "

  private[chat] val StepAction: ActionEntry = ActionRegistry.byName("wizard.step").get
  private[chat] val ConfirmAction: ActionEntry = ActionRegistry.byName("wizard.confirm").get

  private[chat] def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  /** Flow answers of a session (engine-reserved `_`-keys filtered out). */
  private[chat] def dataOf(session: ConversationSession): Map[String, String] =
    WizardDocument.dataFromJson(session.data).filterNot(_._1.startsWith("_"))

  private[chat] def historyOf(session: ConversationSession): List[String] =
    WizardDocument
      .dataFromJson(session.data)
      .get(HistoryKey)
      .toList
      .flatMap(_.split(",").toList.filter(_.nonEmpty))

  private[chat] def selectedOf(session: ConversationSession): List[String] =
    WizardDocument
      .dataFromJson(session.data)
      .get(SelectedKey)
      .toList
      .flatMap(_.split(",").toList.filter(_.nonEmpty))

  private[chat] def failuresOf(session: ConversationSession): Int =
    WizardDocument.dataFromJson(session.data).get(FailuresKey).flatMap(_.toIntOption).getOrElse(0)

  private[chat] def isGrace(session: ConversationSession): Boolean =
    WizardDocument.dataFromJson(session.data).contains(GraceKey)

  private[chat] def sessionJson(data: Map[String, String], history: List[String], grace: Boolean): String =
    WizardDocument.dataToJson(
      data
        ++ Option.when(history.nonEmpty)(HistoryKey -> history.mkString(","))
        ++ Option.when(grace)(GraceKey -> "1")
    )

/** The wizard engine (DESIGN.md section 4.6, ROADMAP M0.12b): one [[ChatHandler]] that owns `conversation_sessions` and
  * routes each stamped event to exactly one of the active wizard step, the global interrupts (`/cancel`, `/menu`), a
  * new-flow start, or the wrapped inner handler. Session work runs inside the mediator's per-event transaction
  * (persist-then-send: the step, `step_seq` and `last_prompt` commit before the prompt is delivered); direct-mode
  * tokens (dose actions) bypass sessions entirely.
  *
  * Design decisions recorded for the slice:
  *   - step prompts replace the tapped message (`OutboundMessage.replaces = source`); old controls die by `step_seq`
  *     staleness and by slot deletion on abort, so no separate finalize op is needed on the step path.
  *   - `[Back]`/`[Cancel]` render as real controls on choice steps and as a typed hint on text/form steps, so a bare
  *     digit is never eaten while the wizard waits for free text (DESIGN.md section 4.6 step 5).
  */
final class WizardEngine(
    flows: Map[String, Flow],
    flowCommands: Map[String, String],
    codec: CallbackCodec,
    clock: Clock,
    inner: ChatHandler,
    uow: UnitOfWork,
    adapters: Map[String, ChatAdapter],
    idleAfter: Duration = WizardEngine.IdleAfter
) extends ChatHandler:

  import WizardEngine.*

  private val formRunner = FormRunner(codec)

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    event.body match
      case Inbound.InteractionSubmitted(ref, source) if isWizardAction(ref) =>
        onWizardTap(event, principal, ref, source, tx)
      case Inbound.FormSubmitted(_, ref, fields) if isWizardAction(ref) =>
        onFormSubmit(event, principal, ref, fields, tx)
      case msg: Inbound.MessageReceived =>
        onMessage(event, principal, msg, tx)
      case Inbound.CommandInvoked(name, _, _) =>
        onCommand(event, principal, name, tx)
      case _ => inner.handle(event, principal, tx)

  private def isWizardAction(ref: CallbackRef): Boolean =
    ActionRegistry.byId(ref.actionId).exists(_.requiresSession)

  private def principalKeyOf(principal: Principal): String =
    principal.accountId.map(_.uuid.toString).getOrElse(principal.identityId.uuid.toString)

  private def loadSession(event: InboundEvent, principal: Principal, tx: Tx): Option[ConversationSession] =
    tx.sessions.loadForUpdate(principalKeyOf(principal), event.vendor, event.chat.chatId)

  // ---------- Wizard-bound interactions ----------

  private def onWizardTap(
      event: InboundEvent,
      principal: Principal,
      ref: CallbackRef,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    loadSession(event, principal, tx) match
      case None          => Reply(toast = Some(ReminderCopy.staleControlToast))
      case Some(session) =>
        if ref.value != session.stepSeq then staleTap(session)
        else
          tx.slots.loadForUpdate(ref.subject) match
            case Some(slot) if slot.sessionId.contains(session.id) =>
              WizardDocument.slotPayloadKey(slot.payload) match
                case BackKey                    => goBack(event, session, source, tx)
                case CancelKey | GraceCancelKey => abort(session, source, tx)
                case GraceContinueKey           => rerender(event, session, source, tx)
                case ConfirmKeepKey             => rerender(event, session, source, tx)
                case DoneKey                    => submitSelection(event, principal, session, source, tx)
                case key if key.startsWith(ConfirmCancelPrefix) =>
                  val flowId = key.drop(ConfirmCancelPrefix.length)
                  cleanup(session, tx)
                  flows.get(flowId) match
                    case Some(flow) => begin(event, principal, flow, source, tx)
                    case None       => Reply(toast = Some(ReminderCopy.staleControlToast))
                case key => choose(event, principal, session, key, source, tx)
            case _ => staleTap(session)

  /** A stale `step_seq` tap: the toast and the current prompt again (DESIGN.md section 4.6). */
  private def staleTap(session: ConversationSession): Reply =
    Reply(
      followUps = session.lastPrompt.map(OutboundMessage.fromJson).toList,
      toast = Some(ReminderCopy.staleControlToast)
    )

  private def choose(
      event: InboundEvent,
      principal: Principal,
      session: ConversationSession,
      key: String,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    stepOf(session) match
      case None                                           => Reply(toast = Some(ReminderCopy.failureToast))
      case Some((_, step)) if togglesInPlace(event, step) => toggle(event, session, step, key, source, tx)
      case Some((flow, step))                             =>
        step.accept(StepInput.Chosen(key), dataOf(session)) match
          case Left(reason)   => reprompt(event, session, step, reason, source, tx)
          case Right(through) => transition(event, principal, session, flow, through, source, tx)

  // ---------- Toggle keyboard (ADR-015 addendum 1) ----------

  /** A multi-select step on a vendor with buttons is one message whose keyboard is edited on each tap. */
  private def togglesInPlace(event: InboundEvent, step: Step): Boolean =
    step.kind match
      case StepKind.Choices(_, _, maxSelect, _) => maxSelect > 1 && hasButtons(event.vendor)
      case _                                    => false

  private def hasButtons(vendor: String): Boolean =
    adapters.get(vendor).exists(_.capabilities.buttons)

  /** Flips one option and edits the prompt in place. `step_seq` stays, so a tap made on the keyboard as it was before
    * this edit arrived still counts.
    */
  private def toggle(
      event: InboundEvent,
      session: ConversationSession,
      step: Step,
      key: String,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    val before = selectedOf(session)
    val selected = if before.contains(key) then before.filterNot(_ == key) else before :+ key
    val data = dataOf(session)
    val prompt = renderStepPrompt(
      event.chat,
      event.principal.flatMap(_.accountId).map(_.uuid),
      session.id,
      step,
      data,
      session.stepSeq,
      tx,
      selected
    ).copy(dedupeKey = s"wizard:${session.id}:${session.stepSeq}:toggle:${event.eventId.uuid}")
    tx.sessions.save(
      session.copy(
        data = sessionJson(data + (SelectedKey -> selected.mkString(",")), historyOf(session), isGrace(session)),
        lastPrompt = Some(OutboundMessage.toJson(prompt)),
        expiresAt = clock.now().plus(idleAfter)
      )
    )
    Reply(replace = Some(prompt.copy(replaces = source)))

  /** `[Done]`: the selection, in option order, goes to the step as one [[StepInput.ChosenMany]]. */
  private def submitSelection(
      event: InboundEvent,
      principal: Principal,
      session: ConversationSession,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    stepOf(session) match
      case Some((flow, step @ Step(_, StepKind.Choices(options, _, _, _), _, _, _, _))) =>
        val data = dataOf(session)
        val selected = selectedOf(session)
        val keys = options(data).map(_._1).filter(selected.contains)
        step.accept(StepInput.ChosenMany(keys), data) match
          case Left(reason)   => reprompt(event, session, step, reason, source, tx)
          case Right(through) => transition(event, principal, session, flow, through, source, tx)
      case _ => staleTap(session)
  private def onFormSubmit(
      event: InboundEvent,
      principal: Principal,
      ref: CallbackRef,
      fields: Map[String, String],
      tx: Tx
  ): Reply =
    loadSession(event, principal, tx) match
      case None          => Reply(toast = Some(ReminderCopy.staleControlToast))
      case Some(session) =>
        if ref.value != session.stepSeq then staleTap(session)
        else
          tx.slots.loadForUpdate(ref.subject) match
            case Some(slot) if slot.sessionId.contains(session.id) =>
              stepOf(session) match
                case None               => Reply(toast = Some(ReminderCopy.failureToast))
                case Some((flow, step)) =>
                  step.accept(StepInput.FormAnswered(fields), dataOf(session)) match
                    case Left(reason)   => reprompt(event, session, step, reason, None, tx)
                    case Right(through) => transition(event, principal, session, flow, through, None, tx)
            case _ => staleTap(session)

  // ---------- Free text ----------

  private def onMessage(event: InboundEvent, principal: Principal, msg: Inbound.MessageReceived, tx: Tx): Reply =
    loadSession(event, principal, tx) match
      case None          => inner.handle(event, principal, tx)
      case Some(session) =>
        stepOf(session) match
          case None               => inner.handle(event, principal, tx)
          case Some((flow, step)) =>
            val text = msg.text.trim
            val navBack = text.equalsIgnoreCase(Labels.Back)
            val navCancel = text.equalsIgnoreCase(Labels.Cancel)
            step.kind match
              case StepKind.Choices(_, _, maxSelect, acceptsText) if maxSelect > 1 || acceptsText =>
                // A multi-select picker also takes typed selections ("1 3 5", "Mon, Wed") on the text tiers (M1.9);
                // a step that accepts text takes typed input on every tier.
                if navBack then goBack(event, session, None, tx)
                else if navCancel then abort(session, None, tx)
                else
                  step.accept(StepInput.TextEntered(text), dataOf(session)) match
                    case Left(reason)   => reprompt(event, session, step, reason, None, tx)
                    case Right(through) => transition(event, principal, session, flow, through, None, tx)
              case StepKind.Choices(_, _, _, _) => inner.handle(event, principal, tx)
              case StepKind.Text                =>
                if navBack then goBack(event, session, None, tx)
                else if navCancel then abort(session, None, tx)
                else
                  step.accept(StepInput.TextEntered(text), dataOf(session)) match
                    case Left(reason)   => reprompt(event, session, step, reason, None, tx)
                    case Right(through) => transition(event, principal, session, flow, through, None, tx)
              case StepKind.Form(_, title, fields) =>
                tx.formRuns.loadForUpdate(session.id) match
                  case None      => inner.handle(event, principal, tx)
                  case Some(run) =>
                    if navBack then goBack(event, session, None, tx)
                    else if navCancel then abort(session, None, tx)
                    else
                      formRunner.accept(tx, run, fields, text) match
                        case FormRunner.AnswerOutcome.Invalid(reason) =>
                          formQuestion(session, title, fields, run, run.fieldIndex, Some(reason), tx)
                        case FormRunner.AnswerOutcome.Advanced(nextIndex) =>
                          formQuestion(session, title, fields, run, nextIndex, None, tx)
                        case FormRunner.AnswerOutcome.Completed(submitted) =>
                          step.accept(StepInput.FormAnswered(submitted.fields), dataOf(session)) match
                            case Left(reason)   => reprompt(event, session, step, reason, None, tx)
                            case Right(through) =>
                              transition(event, principal, session, flow, through, None, tx)

  /** One FormRunner question; becomes the session's `last_prompt` so a restart re-asks the current field. The question
    * shows the field's current answer when the run carries one (M1.9 `[Back]` pre-fill).
    */
  private def formQuestion(
      session: ConversationSession,
      title: String,
      fields: List[Field],
      run: FormRun,
      index: Int,
      notice: Option[String],
      tx: Tx
  ): Reply =
    val current = fields.lift(index).flatMap(f => WizardDocument.answersFromJson(run.answers).get(f.key))
    val message = OutboundMessage(
      body = notice.map(paragraph).toList ++ Renderer.formQuestionNodes(title, fields, index, current),
      dedupeKey = s"wizard:${session.id}:${session.stepSeq}:q$index:${UUID.randomUUID()}",
      correlationId = s"wizard:${session.id}"
    )
    tx.sessions.save(
      session.copy(
        lastPrompt = Some(OutboundMessage.toJson(message)),
        expiresAt = clock.now().plus(idleAfter)
      )
    )
    Reply(replace = Some(message))

  // ---------- Commands ----------

  private def onCommand(event: InboundEvent, principal: Principal, name: String, tx: Tx): Reply =
    name match
      case "cancel" =>
        loadSession(event, principal, tx) match
          case Some(session) => abort(session, None, tx)
          case None          =>
            Reply(followUps =
              List(
                OutboundMessage(
                  body = List(paragraph(WizardCopy.nothingToCancel)),
                  dedupeKey = s"wizard:nothing:${event.eventId.uuid}",
                  correlationId = s"${event.vendor}:${event.vendorEventId}"
                )
              )
            )
      case "menu" =>
        // Global interrupt: an active setup is dropped silently, then the menu command runs (M1.9 owns the menu).
        loadSession(event, principal, tx).foreach(cleanup(_, tx))
        inner.handle(event, principal, tx)
      case other =>
        flowCommands.get(other).flatMap(flows.get) match
          case Some(flow) => startOrConfirm(event, principal, flow, tx)
          case None       => inner.handle(event, principal, tx)

  /** Starts a flow by id for the event's chat, honouring the "Cancel the current setup?" confirmation when a session is
    * active (M0.12d: `ConversationStarted` and `/start` both begin the account-create flow through this).
    */
  def startFlow(event: InboundEvent, principal: Principal, flowId: String, tx: Tx): Reply =
    flows.get(flowId) match
      case Some(flow) => startOrConfirm(event, principal, flow, tx)
      case None       => Reply(toast = Some(ReminderCopy.failureToast))

  /** A new flow while a session is active asks "Cancel the current setup?" first (ROADMAP M0.12b). */
  private def startOrConfirm(event: InboundEvent, principal: Principal, flow: Flow, tx: Tx): Reply =
    loadSession(event, principal, tx) match
      case None          => begin(event, principal, flow, None, tx)
      case Some(session) =>
        val accountId = principal.accountId.map(_.uuid)
        val prompt = OutboundMessage(
          body = List(paragraph(WizardCopy.cancelCurrentSetup)),
          blocks = List(
            Block.Choices(
              ChoiceSet(
                id = "wizard.confirm_cancel",
                choices = List(
                  Choice.callback(
                    Labels.Yes,
                    mint(
                      event.chat,
                      accountId,
                      session.id,
                      session.stepSeq,
                      StepAction,
                      ConfirmCancelPrefix + flow.id,
                      tx
                    ).wire,
                    ChoiceStyle.Danger
                  ),
                  Choice.callback(
                    Labels.No,
                    mint(event.chat, accountId, session.id, session.stepSeq, StepAction, ConfirmKeepKey, tx).wire
                  )
                )
              )
            )
          ),
          dedupeKey = s"wizard:${session.id}:${session.stepSeq}:confirm-cancel",
          correlationId = s"wizard:${session.id}"
        )
        tx.sessions.save(
          session.copy(
            lastPrompt = Some(OutboundMessage.toJson(prompt)),
            expiresAt = clock.now().plus(idleAfter)
          )
        )
        Reply(replace = Some(prompt))

  private def begin(
      event: InboundEvent,
      principal: Principal,
      flow: Flow,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    val sessionId = UUID.randomUUID()
    val step = flow.steps(flow.firstStep)
    val accountId = principal.accountId.map(_.uuid)
    val entered = step.onEnter(Map.empty, accountId, tx)
    val prompt = renderStepPrompt(event.chat, accountId, sessionId, step, entered, 0, tx)
    val now = clock.now()
    tx.sessions.insert(
      ConversationSession(
        id = sessionId,
        principalKey = principalKeyOf(principal),
        vendor = event.vendor,
        chatId = event.chat.chatId,
        flow = flow.id,
        step = step.id,
        stepSeq = 0,
        data = sessionJson(entered, Nil, grace = false),
        version = 1,
        lastPrompt = Some(OutboundMessage.toJson(prompt)),
        expiresAt = now.plus(idleAfter),
        createdAt = now,
        updatedAt = now
      )
    )
    Reply(replace = Some(prompt.copy(replaces = source)))

  // ---------- Step machinery ----------

  private def stepOf(session: ConversationSession): Option[(Flow, Step)] =
    for flow <- flows.get(session.flow); step <- flow.steps.get(session.step)
    yield (flow, step)

  private def goBack(event: InboundEvent, session: ConversationSession, source: Option[MessageHandle], tx: Tx): Reply =
    val history = historyOf(session)
    val target = history.lastOption.getOrElse(session.step)
    flows.get(session.flow).flatMap(_.steps.get(target)) match
      case None       => rerender(event, session, source, tx)
      case Some(step) =>
        advance(event, session, step, dataOf(session), history.dropRight(1), source, tx)

  /** Re-renders the current step (grace continue, confirm keep): same data and history, fresh `step_seq` so the interim
    * prompt's controls go stale.
    */
  private def rerender(
      event: InboundEvent,
      session: ConversationSession,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    stepOf(session) match
      case None            => Reply(toast = Some(ReminderCopy.failureToast))
      case Some((_, step)) =>
        advance(event, session, step, dataOf(session), historyOf(session), source, tx)

  private def reprompt(
      event: InboundEvent,
      session: ConversationSession,
      step: Step,
      reason: String,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    val failures = failuresOf(session) + 1
    val rescue = step.rescue
      .filter(_ => failures >= Step.MaxFailures)
      .flatMap(id => flows.get(session.flow).flatMap(_.steps.get(id)))
    rescue match
      // The user stays in the flow: the rescue step offers buttons of common choices, with the reason shown. The
      // history is cut where Back would have cut it, so Back from there does not return to the failed step.
      case Some(target) =>
        val history = historyOf(session).takeWhile(_ != target.id)
        advance(event, session, target, dataOf(session), history, source, tx, notice = Some(reason))
      case None =>
        val newSeq = session.stepSeq + 1
        // A re-rendered form step starts a fresh run; the old one (if any) is superseded.
        tx.formRuns.delete(session.id)
        val base = renderStepPrompt(
          event.chat,
          event.principal.flatMap(_.accountId).map(_.uuid),
          session.id,
          step,
          dataOf(session),
          newSeq,
          tx
        )
        val prompt = base.copy(body = paragraph(reason) +: base.body)
        val counted = dataOf(session) + (FailuresKey -> failures.toString)
        persist(session, step.id, counted, historyOf(session), newSeq, prompt, tx)
        Reply(replace = Some(prompt.copy(replaces = source)))

  private def transition(
      event: InboundEvent,
      principal: Principal,
      session: ConversationSession,
      flow: Flow,
      through: StepTransition,
      source: Option[MessageHandle],
      tx: Tx
  ): Reply =
    through match
      case StepTransition.Next(stepId, save) =>
        flow.steps.get(stepId) match
          case None       => Reply(toast = Some(ReminderCopy.failureToast))
          case Some(step) =>
            advance(event, session, step, dataOf(session) ++ save, historyOf(session) :+ session.step, source, tx)
      case StepTransition.Complete =>
        val data = dataOf(session)
        cleanup(session, tx)
        flow.onComplete(data, FlowContext(event, principal, tx))
      case StepTransition.Abort => abort(session, source, tx)

  /** The one write path for a step change: render the prompt (minting fresh slots at the new `step_seq`), then persist
    * step/data/`last_prompt`/expiry in the versioned save — persist-then-send, because the mediator delivers the reply
    * only after this transaction commits.
    */
  private def advance(
      event: InboundEvent,
      session: ConversationSession,
      step: Step,
      data: Map[String, String],
      history: List[String],
      source: Option[MessageHandle],
      tx: Tx,
      notice: Option[String] = None
  ): Reply =
    val newSeq = session.stepSeq + 1
    val accountId = event.principal.flatMap(_.accountId).map(_.uuid)
    // A step's onEnter hook reads what its prompt must show (M1.9: find-or-create offer, timezone default).
    val entered = data ++ step.onEnter(data, accountId, tx)
    // A re-rendered form step starts a fresh run; the old one (if any) is superseded.
    tx.formRuns.delete(session.id)
    val rendered = renderStepPrompt(
      event.chat,
      accountId,
      session.id,
      step,
      entered,
      newSeq,
      tx
    )
    val prompt = rendered.copy(body = notice.map(paragraph).toList ++ rendered.body)
    persist(session, step.id, entered, history, newSeq, prompt, tx)
    Reply(replace = Some(prompt.copy(replaces = source)))

  private def persist(
      session: ConversationSession,
      stepId: String,
      data: Map[String, String],
      history: List[String],
      newSeq: Int,
      prompt: OutboundMessage,
      tx: Tx
  ): Unit =
    tx.sessions.save(
      session.copy(
        step = stepId,
        stepSeq = newSeq,
        data = sessionJson(data, history, grace = false),
        lastPrompt = Some(OutboundMessage.toJson(prompt)),
        expiresAt = clock.now().plus(idleAfter)
      )
    )

  private def abort(session: ConversationSession, source: Option[MessageHandle], tx: Tx): Reply =
    cleanup(session, tx)
    Reply(replace =
      Some(
        OutboundMessage(
          body = List(paragraph(WizardCopy.setupCancelled)),
          dedupeKey = s"wizard:${session.id}:${session.stepSeq}:cancelled",
          correlationId = s"wizard:${session.id}",
          replaces = source
        )
      )
    )

  private def cleanup(session: ConversationSession, tx: Tx): Unit =
    tx.formRuns.delete(session.id)
    tx.slots.deleteForSession(session.id)
    tx.sessions.delete(session.id)

  // ---------- Prompt rendering ----------

  /** Renders one step's prompt: body from the flow, controls from the step kind, `[Back]`/`[Cancel]` on every step — as
    * a nav row on choice steps and as a typed hint on text/form steps (so bare digits stay wizard input).
    */
  private[chat] def renderStepPrompt(
      chat: ChatRef,
      accountId: Option[UUID],
      sessionId: UUID,
      step: Step,
      data: Map[String, String],
      stepSeq: Int,
      tx: Tx,
      selected: List[String] = Nil
  ): OutboundMessage =
    val (blocks, extraBody) =
      step.kind match
        case StepKind.Choices(options, minSelect, maxSelect, acceptsText) =>
          def slot(key: String): String = mint(chat, accountId, sessionId, stepSeq, StepAction, key, tx).wire
          // A toggle keyboard marks the options that are on and ends with the choice that submits them.
          val toggled = maxSelect > 1 && hasButtons(chat.vendor)
          val picks = options(data).map: (key, label) =>
            Choice.callback(
              if selected.contains(key) then ControlLimits.fitLabel(SelectedMark + label) else label,
              slot(key)
            )
          val done = Option.when(toggled)(Choice.callback(Labels.Done, slot(DoneKey), ChoiceStyle.Primary))
          val choices = ChoiceSet(
            id = s"wizard.${step.id}",
            choices = picks ++ done,
            minSelect = minSelect,
            maxSelect = maxSelect,
            numberHotkeys = !acceptsText
          )
          (List(Block.Choices(choices), navBlock(chat, accountId, sessionId, stepSeq, tx, !acceptsText)), Nil)
        case StepKind.Text =>
          (Nil, List(paragraph(WizardCopy.textNavHint)))
        case StepKind.Form(formId, title, fields) =>
          val submit = mint(chat, accountId, sessionId, stepSeq, ConfirmAction, FormSubmitKey, tx)
          // A re-rendered form (e.g. `[Back]` from the next step) pre-fills the previous answers: the FormRunner
          // keeps them on an empty re-answer.
          val prefill = fields.flatMap(f => data.get(f.key).map(f.key -> _)).toMap
          formRunner.start(tx, sessionId, formId, submit.wire, prefill)
          val filled = fields.map(f => f.copy(value = data.get(f.key)))
          (
            List(Block.FormBlock(Form(formId, title, filled, submit.wire))),
            List(paragraph(WizardCopy.textNavHint))
          )
    OutboundMessage(
      body = step.render(data) ++ extraBody,
      blocks = blocks,
      dedupeKey = s"wizard:$sessionId:$stepSeq",
      correlationId = s"wizard:$sessionId"
    )

  private def navBlock(
      chat: ChatRef,
      accountId: Option[UUID],
      sessionId: UUID,
      stepSeq: Int,
      tx: Tx,
      numberHotkeys: Boolean
  ): Block =
    Block.Choices(
      ChoiceSet(
        id = "wizard.nav",
        numberHotkeys = numberHotkeys,
        choices = List(
          Choice.callback(Labels.Back, mint(chat, accountId, sessionId, stepSeq, StepAction, BackKey, tx).wire),
          Choice.callback(
            Labels.Cancel,
            mint(chat, accountId, sessionId, stepSeq, StepAction, CancelKey, tx).wire,
            ChoiceStyle.Danger
          )
        )
      )
    )

  private def mint(
      chat: ChatRef,
      accountId: Option[UUID],
      sessionId: UUID,
      stepSeq: Int,
      action: ActionEntry,
      key: String,
      tx: Tx
  ): CallbackToken =
    val slotId = UUID.randomUUID()
    tx.slots.insert(
      CallbackSlot(
        id = slotId,
        accountId = accountId,
        chat = WizardDocument.chatToJson(chat),
        payload = WizardDocument.slotPayloadToJson(key),
        sessionId = Some(sessionId),
        stepSeq = Some(stepSeq),
        expiresAt = Some(clock.now().plus(idleAfter))
      )
    )
    codec.encode(CallbackMode.Slot, action, slotId, stepSeq)

  // ---------- Restart resume ----------

  /** Persist-then-send, restart half (ROADMAP M0.12b): re-sends every stored `last_prompt` still inside its expiry
    * window. The stored prompt's slots still exist, so its controls keep working.
    */
  def resumePending(limit: Int = 500): Int =
    val resumable = uow.transaction: tx =>
      tx.sessions
        .resumable(clock.now(), limit)
        .flatMap(session => session.lastPrompt.map(prompt => (session, OutboundMessage.fromJson(prompt))))
    resumable.foreach: (session, prompt) =>
      WizardDelivery.send(uow, adapters, clock)(
        session.vendor,
        ChatRef(session.vendor, session.chatId),
        prompt,
        kind = "wizard_prompt"
      )
    resumable.size

/** Best-effort direct delivery for the sweeper and restart resume (M0.12b): render, send, record the
  * `rendered_messages.choice_map`. Delivery hardening (outbox rows, retries) is the dispatcher's job from M1.7.
  */
private[chat] object WizardDelivery:

  def send(
      uow: UnitOfWork,
      adapters: Map[String, ChatAdapter],
      clock: Clock
  )(vendor: String, chat: ChatRef, message: OutboundMessage, kind: String): Option[MessageHandle] =
    adapters
      .get(vendor)
      .flatMap: adapter =>
        val (ops, _) = Renderer.render(message, chat, adapter.capabilities, message.dedupeKey, RenderContext())
        ops
          .collectFirst { case op: VendorOp.Send => op }
          .flatMap: op =>
            try
              val handle = adapter.send(op.chat, op.message, op.sendKey)
              try
                uow.transaction: tx =>
                  tx.renderedMessages.record(
                    handle,
                    accountId = None,
                    kind,
                    subjectType = None,
                    subjectId = None,
                    epoch = None,
                    op.message.choiceMap,
                    clock.now()
                  )
                ()
              catch case _: SQLException => ()
              Some(handle)
            catch case _: ChatError => None

  /** finalize (DESIGN.md sections 4.2/4.3): remove the prompt's controls and show the outcome. */
  def finalizePrompt(
      uow: UnitOfWork,
      adapters: Map[String, ChatAdapter],
      clock: Clock
  )(handle: MessageHandle, summary: RichText, chat: ChatRef): Unit =
    adapters
      .get(handle.vendor)
      .foreach: adapter =>
        val (ops, _) = Renderer.renderFinalize(
          handle,
          summary,
          keep = None,
          chat,
          adapter.capabilities,
          s"wizard-finalize:${handle.chatId}:${handle.messageId}:${handle.revision}",
          RenderContext()
        )
        ops.foreach:
          case VendorOp.Edit(h, rendered) =>
            try { adapter.edit(h, rendered); () }
            catch case _: ChatError => ()
          case VendorOp.Delete(h) =>
            try { adapter.delete(h); () }
            catch case _: ChatError => ()
          case op: VendorOp.Send =>
            try
              val sent = adapter.send(op.chat, op.message, op.sendKey)
              try
                uow.transaction: tx =>
                  tx.renderedMessages.record(
                    sent,
                    accountId = None,
                    "wizard_finalize",
                    subjectType = None,
                    subjectId = None,
                    epoch = None,
                    op.message.choiceMap,
                    clock.now()
                  )
                ()
              catch case _: SQLException => ()
            catch case _: ChatError => ()
          case _ => ()
