package dosegram.adapter.telegram

import dosegram.core.ports.Clock

import java.time.Duration
import java.time.Instant
import scala.collection.mutable

/** The fake wire (ARCHITECTURE section 7.1): an in-memory Bot API that records every call, answers the methods in use
  * the way the reference describes them, serves queued updates to `getUpdates`, and plays scripted replies (a 429, a
  * 403, a network failure) for the next call of a method. It lives in the main sources because the launch-path tests of
  * `app` drive the assembled application through it. Nothing here opens a socket.
  */
final class FakeTelegramTransport(
    clock: Clock,
    bot: BotIdentity = FakeTelegramTransport.Bot,
    /** How long an empty `getUpdates` waits, in real time, so that a polling loop under test does not spin. */
    idlePoll: Duration = Duration.ofMillis(20),
    /** The last `message_id` already used in the chat. Telegram never reuses one, so a fake for a restarted process
      * continues after the ids the previous process was answered with.
      */
    lastMessageId: Long = 0L
) extends TelegramTransport:

  import FakeTelegramTransport.*

  private val lock = new Object
  private val recorded = mutable.ListBuffer.empty[RecordedCall]
  private val scripted = mutable.Map.empty[String, mutable.Queue[WireReply]]
  private val pendingUpdates = mutable.ListBuffer.empty[ujson.Value]
  private var commands: ujson.Value = ujson.Arr()
  private var messageSeq = lastMessageId
  private var floodLimits = Option.empty[FloodLimits]
  private val chatBuckets = mutable.Map.empty[Long, (Double, Instant)]

  def calls: List[RecordedCall] = lock.synchronized(recorded.toList)

  def calls(method: String): List[RecordedCall] = calls.filter(_.request.method == method)

  /** The next call of `method` gets `reply` instead of the default answer. Replies queue per method. */
  def replyNext(method: String, reply: WireReply): Unit = lock.synchronized:
    scripted.getOrElseUpdate(method, mutable.Queue.empty).enqueue(reply)
    ()

  def replyNext(method: String, status: Int, body: ujson.Value): Unit =
    replyNext(method, WireReply.Http(status, ujson.write(body)))

  /** Queues updates for `getUpdates`; they stay queued until a call confirms them with a higher `offset`. */
  def enqueueUpdates(updates: ujson.Value*): Unit = lock.synchronized:
    pendingUpdates ++= updates
    ()

  /** Makes the fake refuse message calls beyond `limits` with a 429, as the real API does (section 7.6). */
  def enforce(limits: FloodLimits): Unit = lock.synchronized { floodLimits = Some(limits) }

  override def call(request: WireRequest): WireReply =
    val reply = lock.synchronized:
      val answered = scripted.get(request.method).filter(_.nonEmpty).map(_.dequeue()).getOrElse(answer(request))
      recorded += RecordedCall(clock.now(), request, answered)
      answered
    if request.method == "getUpdates" && isEmptyBatch(reply) && !idlePoll.isZero then Thread.sleep(idlePoll.toMillis)
    reply

  private def isEmptyBatch(reply: WireReply): Boolean = reply == ok(ujson.Arr())

  private def answer(request: WireRequest): WireReply =
    request.method match
      case "getMe"                                      => ok(identityJson)
      case "getUpdates"                                 => ok(ujson.Arr.from(takeUpdates(request.params)))
      case "sendMessage" | "sendPhoto" | "sendDocument" => floodChecked(request)(sentMessage(request))
      case "editMessageText" | "editMessageCaption" | "editMessageReplyMarkup" | "deleteMessage" =>
        floodChecked(request)(ok(ujson.True))
      case "setMyCommands" =>
        commands = request.params.obj.getOrElse("commands", ujson.Arr())
        ok(ujson.True)
      case "getMyCommands"                                               => ok(commands)
      case "answerCallbackQuery" | "setChatMenuButton" | "deleteWebhook" => ok(ujson.True)
      case "setWebhook"                                                  => ok(ujson.True)
      case _                                                             =>
        WireReply.Http(404, ujson.write(ujson.Obj("ok" -> false, "error_code" -> 404, "description" -> "Not Found")))

  private def identityJson: ujson.Obj =
    val json = ujson.Obj("id" -> bot.id.toDouble, "is_bot" -> true)
    bot.username.foreach(name => json("username") = name)
    json

  private def takeUpdates(params: ujson.Obj): List[ujson.Value] =
    val offset = params.obj.get("offset").flatMap(_.numOpt).map(_.toLong)
    offset.foreach(from => pendingUpdates.filterInPlace(TelegramModels.long(_, "update_id").forall(_ >= from)))
    pendingUpdates.toList

  private def sentMessage(request: WireRequest): WireReply =
    messageSeq += 1
    val message = ujson.Obj(
      "message_id" -> messageSeq.toDouble,
      "chat" -> ujson.Obj("id" -> request.params("chat_id"), "type" -> "private"),
      "date" -> clock.now().getEpochSecond.toDouble
    )
    request.method match
      case "sendPhoto" =>
        message("photo") =
          ujson.Arr(ujson.Obj("file_id" -> s"fake-photo-$messageSeq", "width" -> 1280, "height" -> 853))
      case "sendDocument" => message("document") = ujson.Obj("file_id" -> s"fake-document-$messageSeq")
      case _              => ()
    ok(message)

  /** A token bucket per chat on the fake's clock: the documented "one message per second, short bursts tolerated". */
  private def floodChecked(request: WireRequest)(reply: => WireReply): WireReply =
    val chatId = request.params.obj.get("chat_id").flatMap(_.numOpt).map(_.toLong)
    (floodLimits, chatId) match
      case (Some(limits), Some(chat)) =>
        val now = clock.now()
        val (tokens, since) = chatBuckets.getOrElse(chat, (limits.burst.toDouble, now))
        val refilled =
          math.min(limits.burst.toDouble, tokens + Duration.between(since, now).toMillis / 1000.0 * limits.perSecond)
        if refilled < 1.0 then
          chatBuckets(chat) = (refilled, now)
          tooManyRequests(limits.retryAfter)
        else
          chatBuckets(chat) = (refilled - 1.0, now)
          reply
      case _ => reply

object FakeTelegramTransport:

  val Bot: BotIdentity = BotIdentity(555000111L, Some("dosegram_dev_bot"))

  final case class RecordedCall(at: Instant, request: WireRequest, reply: WireReply):
    def method: String = request.method

    /** The `message_id` the fake answered a send with. */
    def sentMessageId: Option[Long] = reply match
      case WireReply.Http(200, body) =>
        TelegramModels.field(ujson.read(body), "result").flatMap(TelegramModels.long(_, "message_id"))
      case _ => None
    def text(key: String): Option[String] = request.params.obj.get(key).flatMap(_.strOpt)
    def number(key: String): Option[Long] = request.params.obj.get(key).flatMap(_.numOpt).map(_.toLong)

  /** Per chat: `burst` message calls at once, refilled at `perSecond`; beyond that a 429 with `retryAfter`. */
  final case class FloodLimits(burst: Int, perSecond: Double, retryAfter: Duration)

  def ok(result: ujson.Value): WireReply = WireReply.Http(200, ujson.write(ujson.Obj("ok" -> true, "result" -> result)))

  def tooManyRequests(retryAfter: Duration): WireReply =
    WireReply.Http(
      429,
      ujson.write(
        ujson.Obj(
          "ok" -> false,
          "error_code" -> 429,
          "description" -> s"Too Many Requests: retry after ${retryAfter.toSeconds}",
          "parameters" -> ujson.Obj("retry_after" -> retryAfter.toSeconds.toDouble)
        )
      )
    )
end FakeTelegramTransport
