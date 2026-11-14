package dosegram.core.application.exports

import dosegram.contracts.AppRoute
import dosegram.contracts.AttachmentRef
import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceAction
import dosegram.contracts.ChoiceSet
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.Inline
import dosegram.contracts.MediaKind
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.chat.ActionEntry
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackMode
import dosegram.core.domain.copy.ExportCopy
import dosegram.core.ports.ExportFile
import dosegram.core.ports.ExportJob
import dosegram.core.ports.ExportKind

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The buttons of the export (ActionRegistry ids 210 to 216). */
object ExportActions:
  val Start: ActionEntry = named("export.start")
  val Confirm: ActionEntry = named("export.confirm")
  val Cancel: ActionEntry = named("export.cancel")
  val Summary: ActionEntry = named("export.summary")
  val SendAsk: ActionEntry = named("export.send_ask")
  val SendAgree: ActionEntry = named("export.send_agree")
  val Send: ActionEntry = named("export.send")

  val all: Set[ActionEntry] = Set(Start, Confirm, Cancel, Summary, SendAsk, SendAgree, Send)

  /** The value of `export.start`: the full archive, or the record summary of the last so many days. */
  val FullArchive: Long = 0L
  val SummaryDays: List[Long] = List(30L, 90L, 365L)

  private def named(name: String): ActionEntry =
    ActionRegistry.byName(name).getOrElse(throw IllegalStateException(s"action '$name' is missing from the registry"))

/** The messages of the export, in the chat and from the worker. `key` keys and correlates one message: the inbound
  * event that answers it, or the job that a worker message is about.
  */
final class ExportMessages(codec: CallbackCodec):

  /** The route of the reports and export screen of the Mini App (MINI_APP section 3.4, S-14). */
  val ReportsRoute: AppRoute = AppRoute("reports")

  /** `/export`: what the export holds, and the two kinds of file. */
  def start(accountId: UUID, key: String): OutboundMessage =
    message(
      "export.start",
      key,
      List(ExportCopy.confirm),
      List(
        List(
          button(ExportCopy.ExportMyData, ExportActions.Start, accountId, ExportActions.FullArchive, primary = true)
        ),
        List(button(ExportCopy.RecordSummary, ExportActions.Summary, accountId, 0L, primary = false))
      )
    )

  def summaryChoice(accountId: UUID, key: String): OutboundMessage =
    val labels = List(ExportCopy.Last30Days, ExportCopy.Last90Days, ExportCopy.Last12Months)
    message(
      "export.summary",
      key,
      List(ExportCopy.summaryChoice),
      labels
        .zip(ExportActions.SummaryDays)
        .map((label, days) => List(button(label, ExportActions.Start, accountId, days, primary = false)))
    )

  /** The confirmation in the chat of a request the Mini App made (BOT_UX BUX-DIV-04). */
  def confirmation(job: ExportJob): OutboundMessage =
    val text = job.kind match
      case ExportKind.FullArchive   => ExportCopy.confirmFromApp
      case ExportKind.RecordSummary => ExportCopy.confirmSummaryFromApp
    message(
      "export.confirm",
      s"export:${job.id}:confirm",
      List(text),
      List(
        List(button(ExportCopy.MakeIt, ExportActions.Confirm, job.id, 0L, primary = true)),
        List(button(ExportCopy.NotNow, ExportActions.Cancel, job.id, 0L, primary = false))
      )
    )

  def text(kind: String, key: String, line: String): OutboundMessage = message(kind, key, List(line), Nil)

  /** BUX-EXP-03: one silent message with the download button, and the warned delivery into the chat below it. */
  def ready(job: ExportJob): OutboundMessage =
    val line = job.kind match
      case ExportKind.FullArchive   => ExportCopy.ready
      case ExportKind.RecordSummary => ExportCopy.summaryReady
    message(
      "export.ready",
      s"export:${job.id}:ready",
      List(line),
      List(
        List(Choice(ExportCopy.DownloadInApp, ChoiceAction.OpenApp(ReportsRoute), ChoiceStyle.Primary)),
        List(button(ExportCopy.SendHere, ExportActions.SendAsk, job.id, 0L, primary = false))
      )
    ).copy(silent = true)

  def failed(job: ExportJob): OutboundMessage = text("export.failed", s"export:${job.id}:failed", ExportCopy.failed)

  /** Stage 1 of the per-export consent `export_to_chat`: the warning of BOT_UX BUX-EXP-02. */
  def warning(exportId: UUID, key: String): OutboundMessage =
    message(
      "export.warning",
      key,
      List(ExportCopy.chatWarning),
      List(List(button(ExportCopy.IUnderstand, ExportActions.SendAgree, exportId, 0L, primary = false)))
    )

  /** Stage 2: the confirming tap carries the minute of the first one, so the consent records both. */
  def sendConfirmation(exportId: UUID, agreedAt: Instant, key: String): OutboundMessage =
    message(
      "export.send",
      key,
      List(ExportCopy.chatWarning, ExportCopy.chatConfirm),
      List(
        List(button(ExportCopy.SendFile, ExportActions.Send, exportId, agreedAt.getEpochSecond / 60, primary = true))
      )
    )

  /** The file itself, as a document; its caption is its text equivalent. */
  def document(job: ExportJob, file: ExportFile, zone: ZoneId, at: Instant, key: String): OutboundMessage =
    // VISUALS DOC-01: in the chat the record summary is `record-summary.pdf`.
    val (caption, fileName) = job.period match
      case Some(period) if job.kind == ExportKind.RecordSummary =>
        (ExportCopy.summaryCaption(period), ExportMessages.SummaryFileName)
      case _ => (ExportCopy.exportCaption(at, zone), file.fileName)
    val attachment =
      AttachmentRef(MediaKind.Document, ExportMessages.assetKey(job.id), file.mime, caption, Some(fileName))
    message("export.file", key, List(caption), Nil).copy(attachment = Some(attachment))

  private def button(label: String, action: ActionEntry, subject: UUID, value: Long, primary: Boolean): Choice =
    val style = if primary then ChoiceStyle.Primary else ChoiceStyle.Secondary
    Choice.callback(label, codec.encode(CallbackMode.Direct, action, subject, value).wire, style)

  private def message(kind: String, key: String, lines: List[String], rows: List[List[Choice]]): OutboundMessage =
    OutboundMessage(
      body = lines.map(line => Node.Paragraph(List(Inline.Text(line)))),
      blocks = rows.zipWithIndex.map((row, index) => Block.Choices(ChoiceSet(s"$kind.$index", choices = row))),
      dedupeKey = s"$kind:$key",
      correlationId = key
    )

object ExportMessages:

  private val AssetPrefix = "export."
  val SummaryFileName = "record-summary.pdf"

  /** The asset key of the file of an export: the asset store of the composition root resolves it to the bytes, for as
    * long as the export has not expired.
    */
  def assetKey(exportId: UUID): String = s"$AssetPrefix$exportId"

  def exportIdOf(assetKey: String): Option[UUID] =
    Option
      .when(assetKey.startsWith(AssetPrefix))(assetKey.drop(AssetPrefix.length))
      .flatMap(id => scala.util.Try(UUID.fromString(id)).toOption)
