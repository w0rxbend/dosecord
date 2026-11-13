package dosegram.adapter.telegram

import dosegram.contracts.*
import dosegram.core.chat.CapabilityProfiles
import dosegram.tests.conformance.*

import java.time.Duration
import java.time.Instant
import scala.collection.mutable

/** The Telegram fake wire of suite A: a scenario's wire events become Bot API updates that arrive through the webhook
  * function, and what the adapter sends is read from the fake transport. A scenario's chat key and wire id are mapped
  * to the numbers Telegram uses, the same number for the same key.
  */
final class TelegramFakeVendorServer(rig: TelegramRig, sink: RecordingSink) extends FakeVendorServer:

  import TelegramFakeVendorServer.*

  private val webhook = WebhookHandler(WebhookConfig(PathSecret, List(HeaderSecret), WebhookConfig.DefaultMaxBodyBytes), rig.adapter.ingest)
  private val updateIds = mutable.Map.empty[String, Long]
  private val chatIds = mutable.Map("dm:owner" -> OwnerChat)
  private val delivered = mutable.ListBuffer.empty[(WireEvent, InboundEvent)]
  private var nextUpdateId = FirstUpdateId
  private var queryCount = 0

  override def onStart(resumeFrom: Option[String]): Unit =
    nextUpdateId = resumeFrom.flatMap(_.toLongOption).map(_ + 1).getOrElse(FirstUpdateId)

  override def chatId(wireChat: String): String = numericChat(wireChat).toString

  override def deliver(event: WireEvent): Unit =
    val before = sink.all.size
    val request = WebhookRequest(PathSecret, Some(HeaderSecret), None, () => ujson.write(update(event)))
    val response = webhook.handle(request)
    require(response == WebhookResponse.Accepted, s"the webhook answered $response")
    sink.all.drop(before).lastOption.foreach(inbound => delivered += ((event, inbound)))

  private def update(event: WireEvent): ujson.Obj =
    val chat = numericChat(event.chatKey)
    val sender = ujson.Obj("id" -> chat.toDouble, "is_bot" -> false)
    val chatJson = ujson.Obj("id" -> chat.toDouble, "type" -> "private")
    val updateId = updateIds.getOrElseUpdate(event.wireKey, { nextUpdateId += 1; nextUpdateId - 1 })
    def message(text: String, replyTo: Option[String]): ujson.Obj =
      val json = ujson.Obj(
        "message_id" -> (updateId + 5000).toDouble,
        "from" -> sender,
        "chat" -> chatJson,
        "date" -> rig.timer.now().getEpochSecond.toDouble,
        "text" -> text
      )
      replyTo.foreach(id => json("reply_to_message") = ujson.Obj("message_id" -> id.toDouble, "chat" -> chatJson))
      json
    event match
      case WireEvent.Message(_, _, text, replyTo) =>
        ujson.Obj("update_id" -> updateId.toDouble, "message" -> message(text, replyTo))
      case WireEvent.Command(_, _, _, text) => ujson.Obj("update_id" -> updateId.toDouble, "message" -> message(text, None))
      case WireEvent.Callback(_, _, callback, source) =>
        queryCount += 1
        ujson.Obj(
          "update_id" -> updateId.toDouble,
          "callback_query" -> ujson.Obj(
            "id" -> s"query-$queryCount",
            "from" -> sender,
            "message" -> ujson.Obj("message_id" -> source.toDouble, "chat" -> chatJson, "date" -> 1, "text" -> "x"),
            "data" -> callback
          )
        )

  private def numericChat(wireChat: String): Long =
    wireChat.toLongOption.getOrElse(chatIds.getOrElseUpdate(wireChat, OwnerChat + chatIds.size))

  override def deliveries: List[(WireEvent, InboundEvent)] = delivered.toList

  /** Sends and edits, each with the id of the message it produced or changed. */
  override def sent: List[SentObservation] =
    rig.wire.calls.flatMap: call =>
      val body = call.text("text").orElse(call.text("caption")).getOrElse("")
      val media = call.method != "sendMessage"
      call.method match
        case "sendMessage" | "sendPhoto" | "sendDocument" =>
          call.sentMessageId.map(id => SentObservation(Some(MessageIds.encode(MessageRef(id, media))), body, body))
        case "editMessageText" => call.number("message_id").map(id => SentObservation(Some(id.toString), body, body))
        case _                 => None

  override def acks: List[AckObservation] = rig.answers.map(call => AckObservation("answerCallbackQuery", call.at))

  /** A fault is the Bot API reply of its fixture, scripted for the method the fault belongs to. */
  override def failNext(fault: VendorFault): Unit =
    val (method, fixture) = fault match
      case VendorFault.RateLimited(_) => ("sendMessage", "flood-429")
      case VendorFault.Blocked        => ("sendMessage", "blocked-403")
      case VendorFault.NotModified    => ("editMessageText", "not-modified-400")
      case VendorFault.EditNotFound   => ("editMessageText", "edit-not-found-400")
      case VendorFault.DeleteWindow   => ("deleteMessage", "delete-window-400")
    val error = BotApiFixtures.error(fixture)
    val body = ujson.copy(error.body)
    fault match
      case VendorFault.RateLimited(seconds) => body("parameters") = ujson.Obj("retry_after" -> seconds.toDouble)
      case _                                => ()
    rig.wire.replyNext(method, error.status, body)

  override def clock: Instant = rig.timer.now()
  override def advanceClock(by: Duration): Unit = rig.timer.advance(by)
  override def logs: List[String] = rig.logs

object TelegramFakeVendorServer:
  val OwnerChat = 7001L
  private val FirstUpdateId = 1000L
  private val PathSecret = "conformance-path-secret"
  private val HeaderSecret = "conformance-header-secret"

/** Suite A wiring for the Telegram adapter: the Telegram profile, a wire that redelivers and answers status codes. */
object TelegramWiring extends AdapterWiring:
  override val vendor: String = TelegramAdapter.Vendor
  override val profile: CapabilityProfile = CapabilityProfiles.Telegram
  override val transportTraits: Set[String] = Set("redelivery", "httpStatus")
  override val defaultChat: ChatRef = ChatRef(vendor, TelegramFakeVendorServer.OwnerChat.toString)

  override def start(scenario: String): AdapterUnderTest =
    val rig = TelegramRig()
    rig.storeAsset("conformance-asset", Array[Byte](1, 2, 3))
    val sink = RecordingSink()
    AdapterUnderTest(rig.adapter, TelegramFakeVendorServer(rig, sink), sink)

/** ROADMAP G1.5 acceptance: suite A passes for the Telegram profile, every scenario applicable. */
class TelegramConformanceSuite extends munit.FunSuite:

  test("suite A: the Telegram adapter passes every scenario on the fake wire, none skipped"):
    val results = ConformanceSuite.suiteA(TelegramWiring)
    val failures = results.collect { case f: ScenarioResult.Failed => f }
    val skipped = results.collect { case s: ScenarioResult.Skipped => s }
    assert(failures.isEmpty, failures.map(f => s"${f.name}: ${f.errors.mkString("; ")}").mkString("\n"))
    assertEquals(skipped, Nil)
    assertEquals(
      results.collect { case ScenarioResult.Passed(name) => name },
      List(
        "event-mapping",
        "duplicate-event",
        "deadline-auto-ack",
        "capability-honesty",
        "rate-limited",
        "not-modified-success",
        "delete-window-permanent",
        "edit-not-found-permanent",
        "markup-golden",
        "text-split",
        "resume",
        "log-hygiene",
        "blocked-unreachable"
      )
    )

  test("the profile's acknowledgement deadline covers the adapter's watchdog"):
    val deadline = CapabilityProfiles.Telegram.ackDeadline.getOrElse(fail("the Telegram profile has an ack deadline"))
    assert(AckTiming.Default.watchdog.compareTo(deadline) <= 0)
