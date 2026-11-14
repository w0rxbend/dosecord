package dosegram.core.application.exports

import dosegram.contracts.CallbackRef
import dosegram.contracts.Inbound
import dosegram.contracts.InboundEvent
import dosegram.contracts.MessageHandle
import dosegram.contracts.OutboundMessage
import dosegram.contracts.Principal
import dosegram.contracts.Reply
import dosegram.core.application.CommandRegistry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.ChatHandler
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportStatus
import dosegram.core.ports.Tx

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The export in the chat (BOT_UX section 20.2, PRODUCT_SPEC R-IMX-2 and R-IMX-3; ROADMAP G3.10, G10.4): `/export`, the
  * choice of the full archive or the record summary, the confirmation of a request the Mini App made, and the file sent
  * into the chat after its warning. The tap in the private chat is the confirmation: no export is made without it
  * (AC-IMX-4). Every button of a job names the job, and a job of another account is a stale button. Anything else goes
  * to `inner`.
  */
final class ExportChat(clock: Clock, requests: ExportRequests, messages: ExportMessages, inner: ChatHandler)
    extends ChatHandler:

  private final case class Turn(event: InboundEvent, accountId: UUID, source: Option[MessageHandle], tx: Tx):
    def key: String = s"${event.vendor}:${event.vendorEventId}"
    def zone: ZoneId = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))

    def show(message: OutboundMessage): Reply =
      source match
        case Some(handle) => Reply(replace = Some(message.copy(replaces = Some(handle))))
        case None         => Reply(followUps = List(message))

  override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
    val answer = event.body match
      case Inbound.CommandInvoked(name, _, _) if name == CommandRegistry.Export.name =>
        Some(
          principal.accountId.fold(toast(IdentityCopy.createFirst))(account =>
            start(Turn(event, account.uuid, None, tx))
          )
        )
      case Inbound.InteractionSubmitted(ref, source) if isExportAction(ref) =>
        Some(
          principal.accountId.fold(toast(ReminderCopy.staleControlToast))(account =>
            tap(Turn(event, account.uuid, source, tx), ref)
          )
        )
      case _ => None
    answer.getOrElse(inner.handle(event, principal, tx))

  private def start(turn: Turn): Reply = turn.show(messages.start(turn.accountId, turn.key))

  private def tap(turn: Turn, ref: CallbackRef): Reply =
    ActionRegistry.byId(ref.actionId) match
      case Some(ExportActions.Start) if ref.subject == turn.accountId   => request(turn, ref.value)
      case Some(ExportActions.Summary) if ref.subject == turn.accountId =>
        turn.show(messages.summaryChoice(turn.accountId, turn.key))
      case Some(ExportActions.Confirm) => onJob(turn, ref.subject)(confirm(turn, _))
      case Some(ExportActions.Cancel)  => onJob(turn, ref.subject)(cancel(turn, _))
      case Some(ExportActions.SendAsk) =>
        onReadyJob(turn, ref.subject)(job => turn.show(messages.warning(job.id, turn.key)))
      case Some(ExportActions.SendAgree) =>
        onReadyJob(turn, ref.subject)(job => turn.show(messages.sendConfirmation(job.id, clock.now(), turn.key)))
      case Some(ExportActions.Send) => onReadyJob(turn, ref.subject)(send(turn, _, ref.value))
      case _                        => stale

  /** `export.start`: the full archive, or the record summary of the last 30, 90 or 365 days. */
  private def request(turn: Turn, value: Long): Reply =
    val order =
      if value == ExportActions.FullArchive then Right(ExportOrder.FullArchive)
      else if ExportActions.SummaryDays.contains(value) then requests.lastDays(turn.tx, turn.accountId, value.toInt)
      else Left(ExportRefusal.PeriodOutOfRange)
    order.flatMap(requests.fromChat(turn.tx, turn.accountId, _)) match
      case Right(_)      => turn.show(messages.text("export.preparing", turn.key, ExportCopy.preparing))
      case Left(refusal) => refused(turn, refusal)

  private def refused(turn: Turn, refusal: ExportRefusal): Reply =
    refusal match
      case ExportRefusal.DailyLimit(next) =>
        turn.show(messages.text("export.limit", turn.key, ExportCopy.limit(next, turn.zone)))
      case ExportRefusal.UnknownAccount | ExportRefusal.PeriodOutOfRange => stale

  private def confirm(turn: Turn, job: ExportJob): Reply =
    if turn.tx.exports.confirm(turn.accountId, job.id, clock.now()) then
      turn.show(messages.text("export.preparing", turn.key, ExportCopy.preparing))
    else stale

  private def cancel(turn: Turn, job: ExportJob): Reply =
    if turn.tx.exports.cancel(turn.accountId, job.id, clock.now()) then
      turn.show(messages.text("export.cancelled", turn.key, ExportCopy.cancelled))
    else stale

  /** The second tap of the warning: the per-export consent with both taps, then the file as a new message. */
  private def send(turn: Turn, job: ExportJob, agreedMinute: Long): Reply =
    val now = clock.now()
    val firstTap = Instant.ofEpochSecond(agreedMinute * 60)
    val agreedAt = if firstTap.isAfter(now) then now else firstTap
    turn.tx.exports.file(turn.accountId, job.id, now) match
      case Some(file) =>
        val notice = ConsentNotice(ExportCopy.WarningVersion, ExportCopy.chatWarning, ConsentCopy.Locale)
        turn.tx.exports.recordChatConsent(turn.accountId, job.id, notice, ConsentTaps(agreedAt, now))
        turn.tx.exports.markDownloaded(turn.accountId, job.id, now)
        val sending = turn.show(messages.text("export.sending", turn.key, ExportCopy.sending))
        sending.copy(followUps = sending.followUps :+ messages.document(job, file, turn.zone, now, turn.key))
      case None => turn.show(messages.text("export.gone", turn.key, ExportCopy.gone))

  private def onJob(turn: Turn, id: UUID)(run: ExportJob => Reply): Reply =
    turn.tx.exports.find(turn.accountId, id).map(run).getOrElse(stale)

  /** A job whose file can still be fetched; otherwise the file has expired. */
  private def onReadyJob(turn: Turn, id: UUID)(run: ExportJob => Reply): Reply =
    onJob(turn, id): job =>
      val now = clock.now()
      val held = job.status == ExportStatus.Ready || job.status == ExportStatus.Downloaded
      if held && job.expiresAt.exists(_.isAfter(now)) then run(job)
      else turn.show(messages.text("export.gone", turn.key, ExportCopy.gone))

  private def stale: Reply = toast(ReminderCopy.staleControlToast)

  private def toast(text: String): Reply = Reply(toast = Some(text))

  private def isExportAction(ref: CallbackRef): Boolean = ActionRegistry.byId(ref.actionId).exists(ExportActions.all)
end ExportChat
