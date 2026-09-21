package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.*
import ox.Ox
import ox.fork

import java.time.Duration
import java.time.Instant
import java.util.UUID

object SessionSweeper:
  final case class Report(graced: Int, aborted: Int, slotsDeleted: Int)

  private final case class GracePlan(session: ConversationSession, prompt: OutboundMessage)
  private final case class AbortPlan(
      session: ConversationSession,
      promptHandle: Option[MessageHandle],
      slotsDeleted: Int
  )

/** The session sweeper fork (ROADMAP M0.12b, DESIGN.md section 4.6): sessions idle for 30 min get one "Still there?
  * [Continue][Cancel]" grace prompt; when the further 30-min grace lapses the session is aborted — its callback slots
  * are deleted and the prompt is finalized "Setup cancelled — nothing saved". Time comes from the injected [[Clock]];
  * tests advance it, no real waiting.
  */
final class SessionSweeper(
    uow: UnitOfWork,
    adapters: Map[String, ChatAdapter],
    codec: CallbackCodec,
    clock: Clock,
    gracePeriod: Duration = WizardEngine.GracePeriod,
    batch: Int = 50
):
  import SessionSweeper.*

  /** One sweep pass: grace newly idle sessions, abort lapsed ones. Selection and mutation commit before delivery. */
  def sweepOnce(): Report =
    val now = clock.now()
    val (gracePlans, abortPlans) = uow.transaction: tx =>
      tx.sessions
        .expiring(now, batch)
        .partitionMap: session =>
          if WizardEngine.isGrace(session) then
            val slotsDeleted = tx.slots.deleteForSession(session.id)
            tx.formRuns.delete(session.id)
            tx.sessions.delete(session.id)
            val promptHandle = tx.renderedMessages.latestPendingPrompt(session.vendor, session.chatId).map(_.handle)
            Right(AbortPlan(session, promptHandle, slotsDeleted))
          else
            val prompt = gracePrompt(session, now, tx)
            tx.sessions.save(
              session.copy(
                data = WizardEngine.sessionJson(
                  WizardEngine.dataOf(session),
                  WizardEngine.historyOf(session),
                  grace = true
                ),
                lastPrompt = Some(OutboundMessage.toJson(prompt)),
                expiresAt = now.plus(gracePeriod)
              )
            )
            Left(GracePlan(session, prompt))
    gracePlans.foreach: plan =>
      WizardDelivery.send(uow, adapters, clock)(
        plan.session.vendor,
        ChatRef(plan.session.vendor, plan.session.chatId),
        plan.prompt,
        kind = "wizard_grace"
      )
    abortPlans.foreach: plan =>
      val chat = ChatRef(plan.session.vendor, plan.session.chatId)
      val summary = List(WizardEngine.paragraph(WizardCopy.setupCancelled))
      plan.promptHandle match
        case Some(handle) => WizardDelivery.finalizePrompt(uow, adapters, clock)(handle, summary, chat)
        case None         =>
          WizardDelivery.send(uow, adapters, clock)(
            plan.session.vendor,
            chat,
            OutboundMessage(
              body = summary,
              dedupeKey = s"wizard:${plan.session.id}:${plan.session.stepSeq}:aborted",
              correlationId = s"wizard:${plan.session.id}"
            ),
            kind = "wizard_aborted"
          )
    Report(gracePlans.size, abortPlans.size, abortPlans.map(_.slotsDeleted).sum)

  /** The sweeper fork of the process model (DESIGN.md section 3): one pass every `period` until the scope ends. */
  def sweepEvery(period: Duration)(using Ox): Unit =
    fork:
      while true do
        Thread.sleep(period.toMillis)
        sweepOnce()
    ()

  /** "Still there? [Continue][Cancel]" at the session's current `step_seq`, so a Continue tap passes the staleness
    * check and a step answered meanwhile makes both grace controls stale.
    */
  private def gracePrompt(session: ConversationSession, now: Instant, tx: Tx): OutboundMessage =
    val chat = ChatRef(session.vendor, session.chatId)
    def mintSlot(key: String): CallbackToken =
      val slotId = UUID.randomUUID()
      tx.slots.insert(
        CallbackSlot(
          id = slotId,
          accountId = None,
          chat = WizardDocument.chatToJson(chat),
          payload = WizardDocument.slotPayloadToJson(key),
          sessionId = Some(session.id),
          stepSeq = Some(session.stepSeq),
          expiresAt = Some(now.plus(gracePeriod))
        )
      )
      codec.encode(CallbackMode.Slot, WizardEngine.StepAction, slotId, session.stepSeq)
    OutboundMessage(
      body = List(WizardEngine.paragraph(WizardCopy.stillThere)),
      blocks = List(
        Block.Choices(
          ChoiceSet(
            id = "wizard.grace",
            choices = List(
              Choice(Labels.Continue, mintSlot(WizardEngine.GraceContinueKey).wire, ChoiceStyle.Primary),
              Choice(Labels.Cancel, mintSlot(WizardEngine.GraceCancelKey).wire, ChoiceStyle.Danger)
            )
          )
        )
      ),
      dedupeKey = s"wizard:${session.id}:${session.stepSeq}:grace",
      correlationId = s"wizard:${session.id}"
    )
