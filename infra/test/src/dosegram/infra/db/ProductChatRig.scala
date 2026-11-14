package dosegram.infra.db

import dosegram.contracts.*
import dosegram.core.application.Application
import dosegram.core.application.CommandRegistry
import dosegram.core.application.capture.CaptureParts
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.CapabilityProfiles
import dosegram.core.chat.ChatMediator
import dosegram.core.chat.FakeAdapter
import dosegram.core.ports.IdSource
import dosegram.core.ports.PurgeList
import dosegram.core.ports.StatusCards
import dosegram.infra.crypto.DataKeys

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.language.implicitConversions

/** The product as production assembles it, over Postgres and a fake Telegram-profile wire: the one handler composition
  * behind the real mediator, every event processed to its end before the next (the durable inbox's `pushAndWait`). A
  * user is driven only through chat input, from `/start` on, so the identity, the delivery channel and the chat id are
  * the ones onboarding writes.
  */
final class ProductChatRig(dataSource: DataSource, val clock: MutableClock, purgeList: PurgeList, val user: String)(
    using ox.Ox
):
  val codec: CallbackCodec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  val keys: DataKeys = DataKeys.development
  val uow: PgUnitOfWork = PgUnitOfWork(dataSource, clock, keys)
  val adapter: FakeAdapter = FakeAdapter(CapabilityProfiles.Telegram)
  val chat: ChatRef = ChatRef("fake", s"dm:$user")
  private val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
  private val parts =
    CaptureParts.over(uow, clock, IdSource.uuidV7(SecureRandom()), StatusCards.textOnly).copy(purgeList = purgeList)
  private val mediator =
    ChatMediator(
      uow,
      adapters,
      codec,
      Application.handler(uow, adapters, codec, clock)(parts),
      clock,
      CommandRegistry.withCaptureByName
    )
  private var seq = 0

  /** One typed line: a `/command` or text. Returns the text of every message the answer sent or edited. */
  def line(input: String): List[String] =
    val body =
      if input.startsWith("/") then Inbound.CommandInvoked(input.drop(1).takeWhile(!_.isWhitespace), Map.empty, input)
      else Inbound.MessageReceived(input, None, truncated = false)
    push(body)

  /** Taps the choice whose label contains `label` on the latest message whose text contains `containing`. */
  def tap(containing: String, label: String): List[String] =
    val (handle, rendered) = adapter.sent.reverse
      .find((_, m) => m.chunks.exists(_.contains(containing)) && m.choiceMap.exists(_.label.contains(label)))
      .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with a choice '$label'"))
    tapWire(rendered.choiceMap.find(_.label.contains(label)).get.callback, Some(handle))

  /** The wire of a choice, to tap it again later: an old button. */
  def wireOf(containing: String, label: String): String =
    adapter.sent.reverse
      .flatMap((_, m) => Option.when(m.chunks.exists(_.contains(containing)))(m.choiceMap).toList.flatten)
      .find(_.label.contains(label))
      .map(_.callback)
      .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with a choice '$label'"))

  /** Taps `label` on the newest message the outbox holds for the account whose text contains `containing`: a message a
    * worker or the Mini App queued, which no dispatcher delivers here.
    */
  def tapQueued(containing: String, label: String): List[String] =
    val conn = dataSource.getConnection
    val queued =
      try
        given java.sql.Connection = conn
        accountId.toList.flatMap: account =>
          sql"SELECT payload::text FROM outbox_messages WHERE account_id = $account ORDER BY created_at, id"
            .query[String]()
            .map(OutboundMessage.fromJson)
      finally conn.close()
    val wire = queued.reverse
      .filter(_.body.toString.contains(containing))
      .flatMap(_.blocks.collect { case Block.Choices(set) => set.choices }.flatten)
      .collectFirst { case Choice(`label`, ChoiceAction.Callback(token), _, _, _) => token }
      .getOrElse(throw new NoSuchElementException(s"no queued message containing '$containing' with '$label'"))
    tapWire(wire, None)

  def tapWire(wire: String, source: Option[MessageHandle]): List[String] =
    val payload = codec.decode(wire).toOption.get
    val ref = CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire)
    push(Inbound.InteractionSubmitted(ref, source))

  /** Onboarding as BOT_UX section 21.1 runs it, with water as the first domain and Kyiv as the zone. */
  def onboard(): UUID =
    line("/start")
    tap("Welcome to Dosegram", "Water")
    tap("I agree that", "I agree")
    tap("Please confirm", "Confirm")
    tap("Pick your timezone", "Europe/Kyiv")
    tap("Correct?", "Yes")
    accountId.getOrElse(throw IllegalStateException("onboarding created no account"))

  /** Answers the two-stage consent card that a first use opened. */
  def consent(): Unit =
    tap("I agree that", "I agree")
    tap("Please confirm", "Confirm")
    ()

  def accountId: Option[UUID] =
    uow.transaction(_.identities.find(PlatformIdentity("fake", user, None))).flatMap(_.accountId).map(_.uuid)

  private def push(body: Inbound): List[String] =
    seq += 1
    val before = adapter.ops.size
    val event = InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      s"$user:$seq",
      clock.now(),
      actor = PlatformIdentity("fake", user, Some("Nick")),
      chat = chat,
      body = body
    )
    assert(mediator.pushAndWait(event), s"event $seq of $user failed")
    adapter.ops
      .drop(before)
      .collect {
        case VendorOp.Send(_, m, _, _) => m.chunks
        case VendorOp.Edit(_, m)       => m.chunks
      }
      .flatten

object ProductChatRig:
  val Start: Instant = Instant.parse("2026-09-21T06:00:00Z")
