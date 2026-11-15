package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.jsonBody
import dosegram.contracts.ChatRef
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.reports.CardDelivery
import dosegram.core.application.reports.ShareFacts
import dosegram.core.domain.copy.ShareCopy
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ReportPage
import dosegram.core.ports.StatusCards
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import sttp.model.StatusCode
import sttp.tapir.*

import java.util.UUID

/** One card the Mini App may send to the chat, named by its kind and by the sentence the card states (VA-IMG-17b). */
final case class ReportCardOffer(kind: String, sentence: String) derives ReadWriter, Schema

/** The cards on offer now. `available` is false without the `images_in_chat` consent; `discreet` asks the app to
  * confirm before sending; `warning` is the sentence shown before any card is sent.
  */
final case class ReportCards(available: Boolean, discreet: Boolean, warning: String, cards: List[ReportCardOffer])
    derives ReadWriter,
      Schema

/** `confirmed` is true once the person confirmed a send in discreet mode. */
final case class ReportCardDeliveryInput(confirmed: Boolean = false) derives ReadWriter, Schema

/** The card is in the report queue; the worker draws it and the chat receives it (ROADMAP G4.3). */
final case class ReportCardDelivery(kind: String, status: String) derives ReadWriter, Schema

/** The visual-reports routes of ARCHITECTURE section 9.5 that exist: `GET /report-cards` and
  * `POST /report-cards/{kind}/deliveries` (I) for the shareable card (VISUALS IMG-17).
  */
object ReportCardEndpoints:
  private val idempotencyKey =
    header[Option[String]]("Idempotency-Key").description("A UUID made when the user taps; required (API-16)")

  val listReportCards =
    Endpoints.secured.get
      .in("report-cards")
      .out(jsonBody[ReportCards])
      .name("listReportCards")
      .description("The shareable cards of the last seven days, each by its sentence.")

  val deliverReportCard =
    Endpoints.secured.post
      .in("report-cards" / path[String]("kind") / "deliveries")
      .in(idempotencyKey)
      .in(jsonBody[ReportCardDeliveryInput])
      .out(statusCode(StatusCode.Accepted))
      .out(jsonBody[ReportCardDelivery])
      .name("deliverReportCard")
      .description("Queues the card for the account's chat, where the report worker sends it.")

  val documented: List[AnyEndpoint] = List(listReportCards, deliverReportCard)

/** The share button of the Insights screen (MINI_APP 7.11): the facts and sentences of the chat's "Share a card"
  * ([[ShareFacts]]), the same card layout, and the same report queue with its limits ([[CardDelivery]]).
  */
final class ReportCardsApi(uow: UnitOfWork, stats: StatsQueryService, cards: StatusCards, delivery: CardDelivery):
  import ReportCardsApi.*

  private val facts = ShareFacts(stats)

  def list(accountId: UUID): ReportCards =
    uow.transactionAs(accountId): tx =>
      val available = tx.consents.isGranted(accountId, ConsentPurpose.ImagesInChat)
      val offers =
        if available then
          facts.of(tx, accountId).map((code, fact) => ReportCardOffer(kindOf(code), ShareFacts.label(fact)))
        else Nil
      ReportCards(available, look(tx, accountId).isDiscreet, ShareCopy.pick, offers)

  def deliver(
      accountId: UUID,
      kind: String,
      key: UUID,
      input: ReportCardDeliveryInput
  ): Either[ApiFailure, ReportCardDelivery] =
    uow.transactionAs(accountId): tx =>
      val cardLook = look(tx, accountId)
      for
        _ <- Either.cond(
          tx.consents.isGranted(accountId, ConsentPurpose.ImagesInChat),
          (),
          ApiFailure.consentRequired(ConsentPurpose.ImagesInChat)
        )
        fact <- facts
          .of(tx, accountId)
          .collectFirst { case (code, fact) if kindOf(code) == kind => fact }
          .toRight(ApiFailure.notFound())
        _ <- Either.cond(!cardLook.isDiscreet || input.confirmed, (), ApiFailure.invalid("confirmed", "required"))
        target <- tx.channels
          .activePrimaryChannels(accountId)
          .find(_.chatId.isDefined)
          .toRight(ApiFailure.invalid("kind", "no_chat"))
        card <- cards.share(fact, cardLook).toRight(ApiFailure.invalid("kind", "image_unavailable"))
        chat = ChatRef(target.vendor, target.chatId.get)
        caption = OutboundMessage(
          body = List(Node.Paragraph(List(Inline.Text(card.altText)))),
          dedupeKey = s"report-card:$key",
          correlationId = s"miniapp:$key"
        )
        // Pages that cannot be queued now (one image underway, the daily limit) come back to be sent as text; the app
        // asks again later instead.
        _ <- Either.cond(
          delivery.answer(tx, accountId, chat, List(ReportPage(card, caption))).isEmpty,
          (),
          ApiFailure.rateLimited(60)
        )
      yield ReportCardDelivery(kind, "queued")

  private def look(tx: Tx, accountId: UUID): CardLook =
    CardLook.of(tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default))

object ReportCardsApi:
  private val Kinds =
    Map(1 -> "share-water-goal", 2 -> "share-sleep-logged", 3 -> "share-mood-logged", 4 -> "share-week-mood")

  private def kindOf(code: Int): String = Kinds.getOrElse(code, s"share-$code")
