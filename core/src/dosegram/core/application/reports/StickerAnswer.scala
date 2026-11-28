package dosegram.core.application.reports

import dosegram.contracts.Block
import dosegram.contracts.Choice
import dosegram.contracts.ChoiceSet
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.domain.copy.StickerCopy

/** The answer to `/mood stickers` once the report worker has tried the set: its message with the words in the account's
  * language, which the worker binds.
  */
private[reports] object StickerAnswer:

  /** The set is published: the link as words, so it can be copied, and as the button that adds the set. */
  def ready(answer: OutboundMessage, link: String): OutboundMessage =
    answer.copy(
      body = List(
        paragraph(StickerCopy.ready),
        Node.Paragraph(List(Inline.Link(link, link))),
        paragraph(StickerCopy.ownSet)
      ),
      // A button opens https links only; any other link stays words.
      blocks = Option
        .when(link.startsWith("https://"))(
          Block.Choices(ChoiceSet("stickers.add", choices = List(Choice.openUrl(StickerCopy.Add, link))))
        )
        .toList
    )

  def failed(answer: OutboundMessage): OutboundMessage =
    answer.copy(body = List(paragraph(StickerCopy.failed)), blocks = Nil)

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))
