package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.jsonBody
import dosegram.contracts.ChatRef
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.OutboundMessage
import dosegram.core.application.insights.ReportViews
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.reports.CardDelivery
import dosegram.core.application.reports.ShareFacts
import dosegram.core.domain.copy.ShareCopy
import dosegram.core.domain.copy.StatsCardCopy
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.insights.TrendPeriod
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
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

/** One period a card of the share builder can be drawn over, and the kind that names the card of that period. */
final case class ReportCardPeriod(days: Int, label: String, kind: String) derives ReadWriter, Schema

/** One card of the share builder (MINI_APP section 7.11): its title and the periods it is drawn over. */
final case class ReportCardChoice(card: String, title: String, periods: List[ReportCardPeriod])
    derives ReadWriter,
      Schema

/** The cards on offer now. `available` is false without the `images_in_chat` consent; `discreet` asks the app to
  * confirm before sending; `warning` is the sentence shown before any card is sent. `cards` are the shareable facts of
  * the last seven days (VISUALS IMG-17); `builder` the cards of the share builder, each over its own periods, under
  * their own `builderTitle` and `builderWarning`, since they carry the user's figures and dates.
  */
final case class ReportCards(
    available: Boolean,
    discreet: Boolean,
    warning: String,
    cards: List[ReportCardOffer],
    builder: List[ReportCardChoice] = Nil,
    builderTitle: Option[String] = None,
    builderWarning: Option[String] = None
) derives ReadWriter,
      Schema

/** A card as the chat would receive it: the PNG, base64, and its text equivalent (VISUALS VA-TX-01). No SVG string is
  * sent (ARCHITECTURE section 9.5.1). `png` is absent when no image can be drawn now; the text is the card then
  * (VA-FB-01).
  */
final case class ReportCardPreview(kind: String, altText: String, png: Option[String] = None) derives ReadWriter, Schema

/** `confirmed` is true once the person confirmed a send in discreet mode. */
final case class ReportCardDeliveryInput(confirmed: Boolean = false) derives ReadWriter, Schema

/** The card is in the report queue; the worker draws it and the chat receives it (ROADMAP G4.3). */
final case class ReportCardDelivery(kind: String, status: String) derives ReadWriter, Schema

/** The visual-reports routes of ARCHITECTURE section 9.5 that exist: `GET /report-cards`,
  * `GET /report-cards/{kind}/preview` and `POST /report-cards/{kind}/deliveries` (I), for the shareable card (VISUALS
  * IMG-17) and the cards of the share builder (IMG-19, IMG-23).
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

  val previewReportCard =
    Endpoints.secured.get
      .in("report-cards" / path[String]("kind") / "preview")
      .out(jsonBody[ReportCardPreview])
      .name("previewReportCard")
      .description("The PNG of a card as the chat would receive it, with its text equivalent; nothing is sent.")

  val documented: List[AnyEndpoint] = List(listReportCards, previewReportCard, deliverReportCard)

/** The share button and the share builder of the Insights screen (MINI_APP 7.11): the facts and sentences of the chat's
  * "Share a card" ([[ShareFacts]]), the week at a glance and the trends of the chat's `/stats`, the same card layouts,
  * and the same report queue with its limits ([[CardDelivery]]).
  */
final class ReportCardsApi(
    uow: UnitOfWork,
    stats: StatsQueryService,
    cards: StatusCards,
    delivery: CardDelivery,
    pictures: CardPage => Option[Array[Byte]]
):
  import ReportCardsApi.*

  private val facts = ShareFacts(stats)

  def list(accountId: UUID): ReportCards =
    uow.transactionAs(accountId): tx =>
      val available = tx.consents.isGranted(accountId, ConsentPurpose.ImagesInChat)
      val offers =
        if available then
          facts.of(tx, accountId).map((code, fact) => ReportCardOffer(kindOf(code), ShareFacts.label(fact)))
        else Nil
      val builder = if available then Builder else Nil
      ReportCards(
        available,
        look(tx, accountId).isDiscreet,
        ShareCopy.pick,
        offers,
        builder,
        Option.when(available)(ShareCopy.builder),
        Option.when(available)(ShareCopy.builderWarning)
      )

  /** The card of `kind` laid out now, as `deliver` would queue it, and drawn after the transaction, as the report
    * worker draws it (ARCHITECTURE RND-05). Nothing is sent.
    */
  def preview(accountId: UUID, kind: String): Either[ApiFailure, ReportCardPreview] =
    val laidOut = uow.transactionAs(accountId): tx =>
      imagesConsent(tx, accountId).flatMap(_ => pageOf(tx, accountId, kind, look(tx, accountId)))
    laidOut.map: card =>
      ReportCardPreview(kind, card.altText, pictures(card).map(java.util.Base64.getEncoder.encodeToString))

  def deliver(
      accountId: UUID,
      kind: String,
      key: UUID,
      input: ReportCardDeliveryInput
  ): Either[ApiFailure, ReportCardDelivery] =
    uow.transactionAs(accountId): tx =>
      val cardLook = look(tx, accountId)
      for
        _ <- imagesConsent(tx, accountId)
        card <- pageOf(tx, accountId, kind, cardLook)
        _ <- Either.cond(!cardLook.isDiscreet || input.confirmed, (), ApiFailure.invalid("confirmed", "required"))
        target <- tx.channels
          .activePrimaryChannels(accountId)
          .find(_.chatId.isDefined)
          .toRight(ApiFailure.invalid("kind", "no_chat"))
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

  private def imagesConsent(tx: Tx, accountId: UUID): Either[ApiFailure, Unit] =
    Either.cond(
      tx.consents.isGranted(accountId, ConsentPurpose.ImagesInChat),
      (),
      ApiFailure.consentRequired(ConsentPurpose.ImagesInChat)
    )

  /** A shareable fact by its kind, or a card of the builder; an unknown kind, or a card with nothing to draw, is
    * absent.
    */
  private def pageOf(tx: Tx, accountId: UUID, kind: String, cardLook: CardLook): Either[ApiFailure, CardPage] =
    val page = kind match
      case WeekGlanceKind =>
        stats
          .summary(tx, accountId, StatsPeriod.Last7Days)
          .toOption
          .map(summary => ReportViews(stats).week(tx, accountId, summary.window))
          .flatMap(cards.weekGlance(_, cardLook))
      case TrendsKind(period) =>
        stats.trends(tx, accountId, period).toOption.flatMap(_.card).flatMap(cards.trends(_, cardLook))
      case _ =>
        facts
          .of(tx, accountId)
          .collectFirst { case (code, fact) if kindOf(code) == kind => fact }
          .flatMap(cards.share(_, cardLook))
    page.toRight(ApiFailure.notFound())

  private def look(tx: Tx, accountId: UUID): CardLook =
    CardLook.of(tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default))

object ReportCardsApi:
  private val Kinds =
    Map(1 -> "share-water-goal", 2 -> "share-sleep-logged", 3 -> "share-mood-logged", 4 -> "share-week-mood")

  private def kindOf(code: Int): String = Kinds.getOrElse(code, s"share-$code")

  /** IMG-19 of the last seven days. */
  private val WeekGlanceKind = "week-glance-7"

  /** IMG-23 of 30 or 90 days: `trends-30`, `trends-90`. */
  private object TrendsKind:
    def apply(period: TrendPeriod): String = s"trends-${period.days}"
    def unapply(kind: String): Option[TrendPeriod] = TrendPeriod.Trends.find(apply(_) == kind)
    def choice: ReportCardChoice =
      ReportCardChoice(
        "trends",
        StatsCardCopy.Trends,
        TrendPeriod.Trends.map(period =>
          ReportCardPeriod(period.days, StatsCardCopy.trendsPeriod(period), apply(period))
        )
      )

  /** The cards of the share builder, in the order of the chat's `/stats`. */
  private def Builder: List[ReportCardChoice] = List(
    ReportCardChoice(
      "week-glance",
      ShareCopy.weekGlanceTitle,
      List(ReportCardPeriod(7, StatsCardCopy.period(StatsPeriod.Last7Days), WeekGlanceKind))
    ),
    TrendsKind.choice
  )
