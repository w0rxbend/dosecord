package dosegram.adapter.telegram

import dosegram.contracts.ChatRef
import dosegram.contracts.InboundEvent
import dosegram.contracts.InboundSink
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys
import dosegram.core.chat.CallbackMode

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The adapter on the fake wire with a manual clock: what a suite reads is what the wire saw (`wire.calls`), what the
  * mediator would get (`events`) and what the adapter logged (`logs`). Waiting never takes real time: a sleep of the
  * flood gate moves the manual clock.
  */
final class TelegramRig(config: TelegramConfig = TelegramRig.webhook):

  val timer: ManualTimer = ManualTimer(TelegramRig.T0)
  val wire: FakeTelegramTransport = FakeTelegramTransport(timer, idlePoll = Duration.ZERO)
  val api: BotApi = BotApi(wire)
  val fileIds: InMemoryFileIdCache = InMemoryFileIdCache()

  private val assets = java.util.concurrent.ConcurrentHashMap[String, Array[Byte]]()
  private val logged = ConcurrentLinkedQueue[String]()
  private val pushed = ConcurrentLinkedQueue[InboundEvent]()
  private val ackWaits = ConcurrentLinkedQueue[Duration]()
  private val expired = java.util.concurrent.atomic.AtomicInteger(0)

  /** The sink of the mediator; `acceptEvents = false` stands for a full queue. */
  @volatile var acceptEvents: Boolean = true
  val sink: InboundSink = event =>
    if acceptEvents then pushed.add(event)
    acceptEvents

  private val metrics: TelegramMetrics = new TelegramMetrics:
    override def ackLatency(waited: Duration): Unit = { ackWaits.add(waited); () }
    override def callbackExpired(): Unit = { expired.incrementAndGet(); () }

  val adapter: TelegramAdapter = TelegramAdapter(
    api,
    config,
    TelegramAdapter.Runtime(
      clock = timer,
      timer = timer,
      media = key => Option(assets.get(key)),
      fileIds = fileIds,
      log = line => { logged.add(line); () },
      metrics = metrics,
      sleep = wait => timer.advance(wait),
      newEventId = () => UUID.randomUUID()
    )
  )

  def events: List[InboundEvent] = pushed.asScala.toList
  def logs: List[String] = logged.asScala.toList
  def ackLatencies: List[Duration] = ackWaits.asScala.toList
  def expiredCallbacks: Int = expired.get()

  def storeAsset(key: String, bytes: Array[Byte]): Unit = { assets.put(key, bytes); () }

  /** Starts the adapter and forgets the calls of the start-up (`getMe`, the default menu button). */
  def started(): TelegramRig =
    adapter.start(sink, None)
    this

  def callsAfterStart: List[FakeTelegramTransport.RecordedCall] =
    wire.calls.filterNot(call => TelegramRig.StartUpMethods.contains(call.method))

  def messageCalls: List[FakeTelegramTransport.RecordedCall] =
    callsAfterStart.filterNot(_.method == "answerCallbackQuery")

  def answers: List[FakeTelegramTransport.RecordedCall] = wire.calls("answerCallbackQuery")

  def ingest(fixture: String): Boolean = adapter.ingest(TelegramRig.decoded(BotApiFixtures.update(fixture)))

  /** Delivers the callback fixture with `data` in place of its placeholder. */
  def deliverTap(data: String): Boolean =
    val update = BotApiFixtures.update("callback-query")
    update("callback_query")("data") = data
    adapter.ingest(TelegramRig.decoded(update))

  /** [[deliverTap]], then the event the rig's own sink received for it. */
  def tap(data: String): InboundEvent =
    deliverTap(data)
    events.last

object TelegramRig:

  val T0: Instant = Instant.parse("2026-11-13T09:00:00Z")

  /** The chat of every fixture. */
  val Chat: ChatRef = ChatRef("telegram", "7001")

  val MiniAppUrl = "https://app.example/"

  val webhook: TelegramConfig = TelegramConfig(
    updates = UpdatesMode.Webhook,
    access = AccessPolicy.Open,
    expectedBotId = Some(FakeTelegramTransport.Bot.id),
    developmentBot = false,
    miniAppUrl = Some(MiniAppUrl)
  )

  private val StartUpMethods = Set("getMe", "setChatMenuButton", "deleteWebhook")

  val codec: CallbackCodec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** A signed direct-mode token of a registered action. */
  def token(action: String, subject: UUID, value: Long = 0L): String =
    val entry = ActionRegistry.byName(action).getOrElse(throw new NoSuchElementException(action))
    codec.encode(CallbackMode.Direct, entry, subject, value).wire

  def decoded(json: ujson.Value): Update =
    TelegramModels.decodeUpdate(json).getOrElse(throw new IllegalArgumentException("fixture is not an update"))
end TelegramRig
