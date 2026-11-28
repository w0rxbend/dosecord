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
  private val aimed = mutable.ListBuffer.empty[(WireRequest => Boolean, WireReply)]
  private val pendingUpdates = mutable.ListBuffer.empty[ujson.Value]
  private var commands: Map[String, ujson.Value] = Map.empty
  private var messageSeq = lastMessageId
  private var floodLimits = Option.empty[FloodLimits]
  private val chatBuckets = mutable.Map.empty[Long, (Double, Instant)]
  private val stickerSets = mutable.LinkedHashMap.empty[String, StickerSetState]
  private var stickerSeq = 0L

  def calls: List[RecordedCall] = lock.synchronized(recorded.toList)

  def calls(method: String): List[RecordedCall] = calls.filter(_.request.method == method)

  /** The next call of `method` gets `reply` instead of the default answer. Replies queue per method. */
  def replyNext(method: String, reply: WireReply): Unit = lock.synchronized:
    scripted.getOrElseUpdate(method, mutable.Queue.empty).enqueue(reply)
    ()

  def replyNext(method: String, status: Int, body: ujson.Value): Unit =
    replyNext(method, WireReply.Http(status, ujson.write(body)))

  /** The first call that `matches` gets `reply` instead of the default answer: one message among the calls of many
    * chats, which a per-method script cannot single out.
    */
  def replyOnceWhen(matches: WireRequest => Boolean, reply: WireReply): Unit = lock.synchronized:
    aimed += ((matches, reply))
    ()

  /** Queues updates for `getUpdates`; they stay queued until a call confirms them with a higher `offset`. */
  def enqueueUpdates(updates: ujson.Value*): Unit = lock.synchronized:
    pendingUpdates ++= updates
    ()

  /** Whether an update queued for `getUpdates` is not yet confirmed: the bot has not taken it into its inbox. */
  def hasPendingUpdates: Boolean = lock.synchronized(pendingUpdates.nonEmpty)

  /** The sticker sets the bot created and has not deleted, by name: the owner, the title and the `file_id` of each
    * sticker in order.
    */
  def stickerSet(name: String): Option[StickerSetState] = lock.synchronized(stickerSets.get(name))

  /** Makes the fake refuse message calls beyond `limits` with a 429, as the real API does (section 7.6). */
  def enforce(limits: FloodLimits): Unit = lock.synchronized { floodLimits = Some(limits) }

  override def call(request: WireRequest): WireReply =
    val reply = lock.synchronized:
      val answered = takeAimed(request)
        .orElse(scripted.get(request.method).filter(_.nonEmpty).map(_.dequeue()))
        .getOrElse(answer(request))
      recorded += RecordedCall(clock.now(), request, answered)
      answered
    if request.method == "getUpdates" && isEmptyBatch(reply) && !idlePoll.isZero then Thread.sleep(idlePoll.toMillis)
    reply

  private def isEmptyBatch(reply: WireReply): Boolean = reply == ok(ujson.Arr())

  private def takeAimed(request: WireRequest): Option[WireReply] =
    val at = aimed.indexWhere((matches, _) => matches(request))
    Option.when(at >= 0)(aimed.remove(at)._2)

  /** Telegram keeps one command list per `language_code`, and one without it. */
  private def languageOf(request: WireRequest): String =
    request.params.obj.get("language_code").flatMap(_.strOpt).getOrElse("")

  private def answer(request: WireRequest): WireReply =
    request.method match
      case "getMe"      => ok(identityJson)
      case "getUpdates" => ok(ujson.Arr.from(takeUpdates(request.params)))
      case "sendMessage" | "sendPhoto" | "sendDocument" | "sendAnimation" => floodChecked(request)(sentMessage(request))
      case "sendMediaGroup"                                               => floodChecked(request)(sentAlbum(request))
      case "editMessageText" | "editMessageCaption" | "editMessageReplyMarkup" | "deleteMessage" =>
        floodChecked(request)(ok(ujson.True))
      case "setMyCommands" =>
        commands = commands.updated(languageOf(request), request.params.obj.getOrElse("commands", ujson.Arr()))
        ok(ujson.True)
      case "getMyCommands" => ok(commands.getOrElse(languageOf(request), ujson.Arr()))
      case "answerCallbackQuery" | "setChatMenuButton" | "deleteWebhook" | "sendChatAction" => ok(ujson.True)
      case "setWebhook"                                                                     => ok(ujson.True)
      case method if StickerMethods(method)                                                 => stickerAnswer(request)
      case _                                                                                =>
        WireReply.Http(404, ujson.write(ujson.Obj("ok" -> false, "error_code" -> 404, "description" -> "Not Found")))

  /** The sticker set methods as the reference describes them (research brief 04 section 3.2): a set is created once per
    * name, `getStickerSet` lists its stickers in order, and a method that names no set answers 400
    * `STICKERSET_INVALID`.
    */
  private def stickerAnswer(request: WireRequest): WireReply =
    val params = request.params.obj
    def name = params.get("name").flatMap(_.strOpt).getOrElse("")
    def fresh(): String =
      stickerSeq += 1
      s"fake-sticker-$stickerSeq"
    def count(stickers: ujson.Value): Int = stickers.arrOpt.map(_.size).getOrElse(0)
    request.method match
      case "getStickerSet" =>
        stickerSets.get(name).fold(stickerSetInvalid)(set => ok(stickerSetJson(name, set)))
      case "createNewStickerSet" =>
        if stickerSets.contains(name) then badRequest("Bad Request: sticker set name is already occupied")
        else
          val owner = params.get("user_id").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
          val title = params.get("title").flatMap(_.strOpt).getOrElse("")
          val files = List.fill(count(params.getOrElse("stickers", ujson.Arr())))(fresh())
          stickerSets(name) = StickerSetState(owner, title, files)
          ok(ujson.True)
      case "addStickerToSet" =>
        stickerSets
          .get(name)
          .fold(stickerSetInvalid): set =>
            stickerSets(name) = set.copy(stickers = set.stickers :+ fresh())
            ok(ujson.True)
      case "replaceStickerInSet" =>
        val old = params.get("old_sticker").flatMap(_.strOpt).getOrElse("")
        stickerSets
          .get(name)
          .filter(_.stickers.contains(old))
          .fold(stickerSetInvalid): set =>
            stickerSets(name) = set.copy(stickers = set.stickers.map(id => if id == old then fresh() else id))
            ok(ujson.True)
      case "deleteStickerFromSet" =>
        val sticker = params.get("sticker").flatMap(_.strOpt).getOrElse("")
        stickerSets
          .find(_._2.stickers.contains(sticker))
          .fold(badRequest("Bad Request: STICKER_INVALID")): (setName, set) =>
            stickerSets(setName) = set.copy(stickers = set.stickers.filterNot(_ == sticker))
            ok(ujson.True)
      case "setStickerSetTitle" =>
        stickerSets
          .get(name)
          .fold(stickerSetInvalid): set =>
            stickerSets(name) = set.copy(title = params.get("title").flatMap(_.strOpt).getOrElse(set.title))
            ok(ujson.True)
      case _ => // deleteStickerSet
        stickerSets.remove(name).fold(stickerSetInvalid)(_ => ok(ujson.True))

  private def stickerSetJson(name: String, set: StickerSetState): ujson.Obj =
    ujson.Obj(
      "name" -> name,
      "title" -> set.title,
      "sticker_type" -> "regular",
      "stickers" -> ujson.Arr.from(set.stickers.map: fileId =>
        ujson.Obj(
          "file_id" -> fileId,
          "file_unique_id" -> s"u-$fileId",
          "type" -> "regular",
          "width" -> 512,
          "height" -> 512,
          "is_animated" -> false,
          "is_video" -> false,
          "set_name" -> name
        ))
    )

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
      // The Bot API answers an animation with both objects; the document names the same file.
      case "sendAnimation" =>
        message("animation") = ujson.Obj("file_id" -> s"fake-animation-$messageSeq", "width" -> 512, "height" -> 512)
        message("document") = ujson.Obj("file_id" -> s"fake-animation-$messageSeq")
      case _ => ()
    ok(message)

  /** One photo message per item of the group, each with its own `message_id` and `file_id`, as the reference answers.
    */
  private def sentAlbum(request: WireRequest): WireReply =
    val items = request.params.obj.get("media").flatMap(_.arrOpt).map(_.size).getOrElse(0)
    val messages = (1 to items).map: _ =>
      messageSeq += 1
      ujson.Obj(
        "message_id" -> messageSeq.toDouble,
        "chat" -> ujson.Obj("id" -> request.params("chat_id"), "type" -> "private"),
        "date" -> clock.now().getEpochSecond.toDouble,
        "photo" -> ujson.Arr(ujson.Obj("file_id" -> s"fake-photo-$messageSeq", "width" -> 1280, "height" -> 853))
      )
    ok(ujson.Arr.from(messages))

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

  /** One sticker set of the fake: its owner, its title and the `file_id` of each sticker in order. */
  final case class StickerSetState(owner: Long, title: String, stickers: List[String])

  private val StickerMethods = Set(
    "getStickerSet",
    "createNewStickerSet",
    "addStickerToSet",
    "replaceStickerInSet",
    "deleteStickerFromSet",
    "setStickerSetTitle",
    "deleteStickerSet"
  )

  private def badRequest(description: String): WireReply =
    WireReply.Http(400, ujson.write(ujson.Obj("ok" -> false, "error_code" -> 400, "description" -> description)))

  private def stickerSetInvalid: WireReply = badRequest("Bad Request: STICKERSET_INVALID")

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
