package dosegram.core.application.capture

import dosegram.contracts.AppRoute
import dosegram.contracts.AttachmentRef
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceAction
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.MediaKind
import dosegram.contracts.Reply
import dosegram.core.domain.copy.ImportCopy
import dosegram.core.domain.imports.CsvTemplate

/** Imports in the chat (ROADMAP G10.5; BOT_UX section 11.1 rule 7; PRODUCT_SPEC R-IMX-7): `/import` offers the empty
  * templates and opens the import screen of the app, where the file is read on the device. A document sent to the chat
  * is never downloaded: the answer says where imports happen and why, the 20 MB limit of a bot included.
  */
private[capture] final class ImportChat(tokens: Tokens):

  /** The import card, after the `import` consent. */
  def card(turn: Turn): Reply =
    val templates = CsvTemplate.values.toList
      .map(template =>
        Choice.callback(
          ImportCopy.templateLabel(template),
          tokens.direct(CaptureActions.ImportTemplate, turn.accountId, template.ordinal.toLong)
        )
      )
      .grouped(2)
      .toList
    turn.show(turn.message("import.card", List(ImportCopy.card, ImportCopy.templatesHint), List(openApp) :: templates))

  /** One template as a document: a header row, no record. Its caption names the columns. */
  def template(turn: Turn, value: Long): Option[Reply] =
    CsvTemplate.values
      .lift(value.toInt)
      .map: template =>
        val caption = ImportCopy.templateCaption(template)
        val attachment = AttachmentRef(
          MediaKind.Document,
          ImportChat.assetKey(template),
          CsvTemplate.Mime,
          caption,
          Some(template.fileName)
        )
        val message =
          turn.message(s"import.template.${template.kind}", List(caption), Nil).copy(attachment = Some(attachment))
        Reply(followUps = List(message))

  /** BOT_UX section 11.1 rule 7: the file is not opened; the app imports. */
  def document(turn: Turn): Reply =
    turn.show(turn.message("in.file.import", List(ImportCopy.fileNotOpened, ImportCopy.sizeLimit), List(List(openApp))))

  private def openApp: Choice =
    Choice(ImportCopy.ImportInApp, ChoiceAction.OpenApp(ImportChat.Route), ChoiceStyle.Primary)

object ImportChat:

  /** The import screen of the Mini App (`startapp=import`). */
  val Route: AppRoute = AppRoute("import")

  private val AssetPrefix = "import-template."

  /** The asset key of a template: the asset store of the composition root resolves it to the template's bytes. */
  def assetKey(template: CsvTemplate): String = s"$AssetPrefix${template.kind}"

  def templateOf(assetKey: String): Option[CsvTemplate] =
    Option.when(assetKey.startsWith(AssetPrefix))(assetKey.drop(AssetPrefix.length)).flatMap(CsvTemplate.ofKind)
