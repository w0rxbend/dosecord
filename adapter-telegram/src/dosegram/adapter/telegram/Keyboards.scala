package dosegram.adapter.telegram

import dosegram.contracts.ChatError
import dosegram.contracts.ChoiceAction
import dosegram.contracts.ChoiceStyle
import dosegram.contracts.MessageHandle
import dosegram.contracts.RenderedChoice
import dosegram.contracts.RenderedControls

import java.nio.charset.StandardCharsets.UTF_8

/** The vendor form of a message id. A media message is edited through its caption, and the adapter keeps no state, so
  * the handle itself says which kind the message is: `<id>` for a text message, `m<id>` for a photo or a document.
  */
private[telegram] object MessageIds:

  private val MediaPrefix = "m"

  def encode(ref: MessageRef): String =
    if ref.carriesMedia then s"$MediaPrefix${ref.messageId}" else ref.messageId.toString

  def decode(encoded: String): Option[MessageRef] =
    val media = encoded.startsWith(MediaPrefix)
    encoded.stripPrefix(MediaPrefix).toLongOption.map(MessageRef(_, media))

  def handle(chatId: Long, ref: MessageRef): MessageHandle =
    MessageHandle(TelegramAdapter.Vendor, chatId.toString, encode(ref))

/** Lowers rendered controls into an inline keyboard (BOT_UX section 4.2). `miniAppUrl` is the address of the Mini App;
  * without it an app button is left out, because a button that opens nothing must not be shown (BUX-KB-05).
  */
private[telegram] final class Keyboards(miniAppUrl: Option[String]):

  /** Button rows of every `Buttons` block, in order. A numbered legend is text and has no keyboard. */
  def inline(controls: List[RenderedControls]): InlineKeyboard =
    InlineKeyboard(
      controls
        .collect { case RenderedControls.Buttons(rows) => rows }
        .flatten
        .map(_.flatMap(button))
        .filter(_.nonEmpty)
    )

  private def button(choice: RenderedChoice): Option[InlineButton] =
    choice.action match
      case ChoiceAction.Callback(token) =>
        Some(InlineButton.Callback(choice.label, checked(token), style(choice.style)))
      case ChoiceAction.OpenUrl(url)   => Some(InlineButton.Url(choice.label, url))
      case ChoiceAction.OpenApp(route) =>
        miniAppUrl.map(base => InlineButton.WebApp(choice.label, appUrl(base, route.wire)))

  /** `callback_data` is 1 to 64 bytes (research brief 02, section 7.1). A token outside that is a defect of ours. */
  private def checked(token: String): String =
    val bytes = token.getBytes(UTF_8).length
    if bytes < 1 || bytes > Keyboards.MaxCallbackBytes then
      throw ChatError.Permanent(s"telegram send: callback data of $bytes bytes", channelFatal = false)
    token

  private def style(style: ChoiceStyle): Option[String] =
    style match
      case ChoiceStyle.Primary   => Some("primary")
      case ChoiceStyle.Success   => Some("success")
      case ChoiceStyle.Danger    => Some("danger")
      case ChoiceStyle.Secondary => None

  /** The route travels as the query parameter `route` (MINI_APP section 3.4); its grammar needs no escaping. */
  private def appUrl(base: String, route: String): String =
    val separator = if base.contains("?") then "&" else "?"
    s"$base${separator}route=$route"

private[telegram] object Keyboards:
  val MaxCallbackBytes = 64
