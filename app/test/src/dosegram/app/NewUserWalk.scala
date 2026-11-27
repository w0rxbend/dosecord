package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.adapter.telegram.FakeTelegramTransport.RecordedCall

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import scala.collection.mutable

/** One new user's chat on the fake wire of the launched process, written down as a transcript: every line the user
  * types or taps, and every call the bot makes back (messages, edits, images with their captions, toasts, deletions).
  * The test reads the state of the database only to know when the bot has finished answering.
  */
final class NewUserWalk(
    val clock: SteppedClock,
    val wire: FakeTelegramTransport,
    user: NewUserWalk.User,
    zone: () => ZoneId,
    busy: () => Boolean,
    behind: () => Boolean
):
  import NewUserWalk.*

  private var updateSeq = user.firstUpdate
  private var seen = 0
  private val lines = mutable.ListBuffer.empty[String]
  // The newest keyboard of each message, the most recently changed message last.
  private val keyboards = mutable.LinkedHashMap.empty[Long, List[List[String]]]
  private val callbackData = mutable.Map.empty[(Long, String), String]
  private val shownLabels = mutable.LinkedHashSet.empty[String]

  def transcript: List[String] = lines.toList

  /** Every button label the bot showed in the walk. */
  def labels: Set[String] = shownLabels.toSet

  /** A line of the walk's own: a heading or the reason for the next step. */
  def note(text: String): Unit =
    record()
    lines += ""
    lines += s"-- $text"

  /** Types `text` and records what the bot answered. */
  def say(text: String): Unit =
    lines += s"${time()} you: $text"
    advance(Duration.ofSeconds(5))
    updateSeq += 1
    wire.enqueueUpdates(message(text))
    settle()

  /** Taps the newest button labelled `label` and records what the bot answered. */
  def tap(label: String): Unit = tapWhere(label)(_ == label)

  /** Taps the newest button whose label `matches`. */
  def tapWhere(clue: String)(matches: String => Boolean): Unit =
    val (messageId, label) = awaitButton(matches)
      .getOrElse(throw AssertionError(s"no button '$clue'; last lines:\n${lines.takeRight(25).mkString("\n")}"))
    val data = callbackData.getOrElse((messageId, label), throw AssertionError(s"'$label' is not a callback button"))
    lines += s"${time()} you tap: [$label] on #$messageId"
    advance(Duration.ofSeconds(5))
    updateSeq += 1
    wire.enqueueUpdates(callback(messageId, data))
    settle()

  /** The newest button whose label `matches`, waiting without moving the clock: a worker that runs late on a loaded
    * machine (a reminder already due) can send it after the walk has settled.
    */
  private def awaitButton(matches: String => Boolean): Option[(Long, String)] =
    def newest(): Option[(Long, String)] =
      record()
      keyboards.toList.reverseIterator.flatMap((id, rows) => rows.flatten.find(matches).map(id -> _)).nextOption()
    newest().orElse:
      val deadline = System.nanoTime() + ButtonWaitNanos
      while newest().isEmpty && System.nanoTime() < deadline do Thread.sleep(100)
      // It came late: let the rest of that answer arrive before tapping.
      if newest().isDefined then settle()
      newest()

  /** The labels of the newest message that has a keyboard. */
  def newestButtons: List[String] = keyboards.lastOption.toList.flatMap(_._2.flatten)

  def hasButton(label: String): Boolean = keyboards.valuesIterator.exists(_.flatten.contains(label))

  /** Lets time pass in 30-second steps up to `until`, recording what the bot sends on its own. */
  def passUntil(until: Instant): Unit =
    record()
    while clock.now().isBefore(until) do
      clock.advance(Duration.ofSeconds(30))
      catchUp()
      record()
    settle()

  /** Waits until the workers have done what is due by now, moving the clock only by tenths of a second: on a loaded
    * machine a repeat decided two steps late is folded into the next one, and a send paced behind a delete in the same
    * second would wait while the walk runs the clock on by many minutes.
    */
  private def catchUp(): Unit =
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos
    Thread.sleep(15)
    while behind() && System.nanoTime() < deadline do
      // The adapter paces sends to one per chat and second on the stepped clock.
      clock.advance(Duration.ofMillis(100))
      Thread.sleep(10)

  /** Waits until the bot has answered: nothing in flight and no new call for a while. */
  def settle(): Unit =
    val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos
    var count = -1
    var quietSince = System.nanoTime()
    var done = false
    while !done do
      if System.nanoTime() > deadline then throw AssertionError(s"the bot did not settle; ${lines.takeRight(10)}")
      val now = wire.calls.count(call => !Silent(call.method))
      if now != count || busy() then
        count = now
        quietSince = System.nanoTime()
        // The adapter paces sends to one per chat and second on the stepped clock.
        clock.advance(Duration.ofMillis(500))
      else if System.nanoTime() - quietSince > QuietNanos then done = true
      Thread.sleep(20)
    record()

  private def advance(by: Duration): Unit = clock.advance(by)

  private def time(): String = Clock.format(clock.now().atZone(zone()))

  /** Writes down every call the bot made since the last record. */
  private def record(): Unit =
    val calls = wire.calls
    calls.drop(seen).foreach(call => lines ++= render(call))
    seen = calls.size

  private def render(call: RecordedCall): List[String] =
    val at = time()
    def keyboard(id: Long): List[String] =
      val rows = rowsOf(call)
      keyboards.remove(id)
      keyboards(id) = rows.map(_.map(_.label))
      shownLabels ++= rows.flatten.map(_.label)
      rows.foreach(_.foreach(button => button.data.foreach(data => callbackData((id, button.label)) = data)))
      rows.map(row => "      " + row.map(_.shown).mkString(" "))
    def body(text: String): List[String] = text.linesIterator.map(line => ("      | " + line).stripTrailing).toList
    call.method match
      case "sendMessage" =>
        val id = call.sentMessageId.getOrElse(-1L)
        s"$at bot #$id:" :: body(call.text("text").getOrElse("")) ++ keyboard(id) ++ replyKeyboard(call)
      case "editMessageText" =>
        val id = call.number("message_id").getOrElse(-1L)
        s"$at bot edits #$id:" :: body(call.text("text").getOrElse("")) ++ keyboard(id)
      case "editMessageCaption" =>
        val id = call.number("message_id").getOrElse(-1L)
        s"$at bot edits the caption of #$id:" :: body(call.text("caption").getOrElse("")) ++ keyboard(id)
      case "editMessageReplyMarkup" =>
        val id = call.number("message_id").getOrElse(-1L)
        val rows = keyboard(id)
        s"$at bot changes the buttons of #$id${if rows.isEmpty then " (none left)" else ":"}" :: rows
      case "sendPhoto" =>
        val id = call.sentMessageId.getOrElse(-1L)
        val spoiler = if call.request.params.obj.get("has_spoiler").exists(_.boolOpt.contains(true)) then " (spoiler)" else ""
        s"$at bot #$id [image$spoiler], caption:" :: body(call.text("caption").getOrElse("(no caption)")) ++ keyboard(id)
      case "sendDocument" =>
        val id = call.sentMessageId.getOrElse(-1L)
        s"$at bot #$id [document], caption:" :: body(call.text("caption").getOrElse("(no caption)"))
      case "sendMediaGroup" =>
        val captions = call.request.params.obj.get("media").flatMap(_.arrOpt).toList.flatten
          .map(item => item.obj.get("caption").flatMap(_.strOpt).getOrElse("(no caption)"))
        s"$at bot [album of ${captions.size} images]" :: captions.zipWithIndex.flatMap((c, i) => s"      image ${i + 1}:" :: body(c))
      case "answerCallbackQuery" =>
        call.text("text") match
          case Some(text) =>
            val kind = if call.request.params.obj.get("show_alert").exists(_.boolOpt.contains(true)) then "alert" else "toast"
            List(s"$at bot $kind: $text")
          case None => Nil
      case "deleteMessage"     => List(s"$at bot deletes #${call.number("message_id").getOrElse(-1L)}")
      case "setChatMenuButton" => List(s"$at bot sets the menu button")
      case "setMessageReaction" =>
        val emoji = call.request.params.obj.get("reaction").flatMap(_.arrOpt).toList.flatten
          .flatMap(_.obj.get("emoji").flatMap(_.strOpt))
        List(s"$at bot reacts ${emoji.mkString(" ")} to #${call.number("message_id").getOrElse(-1L)}")
      case silent if Silent(silent) => Nil
      case other                    => List(s"$at bot calls $other")

  private def replyKeyboard(call: RecordedCall): List[String] =
    call.request.params.obj.get("reply_markup").toList.flatMap { markup =>
      markup.obj.get("keyboard").flatMap(_.arrOpt).toList.flatten.map { row =>
        "      reply keyboard: " + row.arr.map(b => b.strOpt.getOrElse(b("text").str)).map(l => s"<$l>").mkString(" ")
      } ++ markup.obj.get("remove_keyboard").filter(_.boolOpt.contains(true)).map(_ => "      (reply keyboard removed)")
    }

  private def message(text: String): ujson.Value =
    ujson.Obj(
      "update_id" -> updateSeq.toDouble,
      "message" -> ujson.Obj(
        "message_id" -> (updateSeq + 900000).toDouble,
        "from" -> from,
        "chat" -> ujson.Obj("id" -> user.id.toDouble, "type" -> "private"),
        "date" -> clock.now().getEpochSecond.toDouble,
        "text" -> text
      )
    )

  private def callback(messageId: Long, data: String): ujson.Value =
    ujson.Obj(
      "update_id" -> updateSeq.toDouble,
      "callback_query" -> ujson.Obj(
        "id" -> s"query-$updateSeq",
        "from" -> from,
        "message" -> ujson.Obj(
          "message_id" -> messageId.toDouble,
          "chat" -> ujson.Obj("id" -> user.id.toDouble, "type" -> "private"),
          "date" -> clock.now().getEpochSecond.toDouble,
          "text" -> "x"
        ),
        "data" -> data
      )
    )

  private def from: ujson.Obj =
    val json = ujson.Obj("id" -> user.id.toDouble, "is_bot" -> false, "first_name" -> user.firstName)
    user.languageCode.foreach(code => json("language_code") = code)
    json

object NewUserWalk:

  /** A person on Telegram: the id, the first name Telegram sends with every update, and the client's language. */
  final case class User(id: Long, firstName: String, languageCode: Option[String], firstUpdate: Long)

  /** The calls of the bot's own upkeep, which the user never sees. */
  private val Silent = Set(
    "getUpdates", "getMe", "setMyCommands", "getMyCommands", "deleteWebhook", "sendChatAction", "setMyDescription",
    "setMyShortDescription", "setMyName", "getMyDescription", "getMyShortDescription", "getMyName", "getChatMenuButton"
  )

  private val QuietNanos = Duration.ofMillis(400).toNanos

  // How long a tap waits, in wall time, for a button that a worker has not sent yet.
  private val ButtonWaitNanos = Duration.ofSeconds(30).toNanos

  private final case class Button(label: String, data: Option[String], kind: String):
    def shown: String = kind match
      case "url"     => s"[$label ->link]"
      case "web_app" => s"[$label ->app]"
      case _         => s"[$label]"

  private def rowsOf(call: RecordedCall): List[List[Button]] =
    call.request.params.obj.get("reply_markup").toList
      .flatMap(_.obj.get("inline_keyboard").flatMap(_.arrOpt).toList.flatten)
      .map(_.arr.toList.map { b =>
        val kind = List("callback_data", "url", "web_app").find(b.obj.contains).getOrElse("other")
        Button(b("text").str, b.obj.get("callback_data").flatMap(_.strOpt), kind)
      })

  private object Clock:
    private val Format = DateTimeFormatter.ofPattern("EEE HH:mm:ss", java.util.Locale.ENGLISH)
    def format(at: java.time.ZonedDateTime): String = Format.format(at)

  /** When the stepped clock is at `local` on the walk's first day in `zone`. */
  def at(day: java.time.LocalDate, local: String, zone: ZoneId): Instant =
    day.atTime(java.time.LocalTime.parse(local)).atZone(zone).toInstant
