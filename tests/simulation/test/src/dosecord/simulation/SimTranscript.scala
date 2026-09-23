package dosecord.simulation

import dosecord.contracts.ChoiceMapEntry
import dosecord.contracts.MessageHandle
import dosecord.contracts.RenderedMessage
import dosecord.core.chat.CallbackCodec
import dosecord.core.ports.Clock

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.collection.mutable

/** The dispatch transcript of the M1.11 simulation (ROADMAP line 92): every call the console adapters made, with the
  * control maps, plus the harness's own script lines (user input echoes, the outage markers, the direct schedule edit).
  *
  * Determinism strategy (the golden gate of the slice: any `decide` or loop change fails without a reviewed golden
  * update):
  *   - every timestamp comes from the injected virtual clock — there is no wall-clock read anywhere in the harness;
  *   - occurrence/account/channel ids are random per run, so they are normalised to stable aliases
  *     (`p1-2026-10-05-t0900`, `p1`, …) before rendering;
  *   - message handles are minted in dispatch order, which is not guaranteed between rows of one dispatcher batch
  *     tied on `next_attempt_at` (the claim's ORDER BY has no unique tiebreak), so a finalize's target renders as the
  *     normalised send key of the message it supersedes, and the entries of one batch are sorted by their normalised
  *     text. Everything outside a batch (mediator replies, toasts) is strictly sequential and stays in order.
  */
final class SimTranscript(clock: Clock):

  private enum Entry:
    case Script(at: Instant, line: String)
    case Adapter(
        at: Instant,
        batch: Long,
        persona: String,
        chatId: String,
        sendKey: String,
        rendered: RenderedMessage,
        handle: MessageHandle
    )

  private val entries = mutable.ListBuffer.empty[Entry]
  private var batchId = 0L
  private var inBatch = false
  // wizard session ids are random per run: aliased to `session-N` in encounter order (deterministic, sequential)
  private val sessionAliases = mutable.HashMap.empty[String, String]
  private var sessionCount = 0

  /** Opens a dispatcher batch: entries recorded until [[endBatch]] belong to one `dispatchOnce` claim and are sorted
    * by normalised text at render time.
    */
  def beginBatch(): Unit =
    batchId += 1
    inBatch = true

  def endBatch(): Unit = inBatch = false

  /** A harness script line (user input echo, outage marker, direct schedule edit). */
  def script(line: String): Unit = entries += Entry.Script(clock.now(), line)

  def record(persona: String, chatId: String, sendKey: String, rendered: RenderedMessage, handle: MessageHandle): Unit =
    entries += Entry.Adapter(clock.now(), if inBatch then batchId else 0L, persona, chatId, sendKey, rendered, handle)

  /** The persona's recorded adapter sends, newest last — what the console user would see on their screen. */
  def messages(persona: String): List[RecordedMessage] =
    entries.toList.collect { case Entry.Adapter(_, _, `persona`, chatId, sendKey, rendered, handle) =>
      RecordedMessage(chatId, sendKey, rendered, handle)
    }

  /** Renders the golden text. `aliases` maps occurrence ids to stable aliases and account ids to persona ids; the
    * codec decodes choice-map callbacks so control maps render as `action(alias, value)` instead of opaque tokens.
    */
  def render(aliases: Aliases, codec: CallbackCodec): String =
    val b = StringBuilder()
    // handle (chatId, messageId) -> the normalised send key of the send that minted it, for finalize targets
    val handleKeys = mutable.HashMap.empty[(String, String), String]
    val pending = mutable.ListBuffer.empty[String]

    def flush(): Unit =
      if pending.nonEmpty then
        b ++= pending.sorted.mkString
        pending.clear()

    entries.toList.foreach {
      case Entry.Script(at, line) =>
        flush()
        b ++= s"${fmt(at)} >> $line\n"
      case Entry.Adapter(at, batch, persona, chatId, sendKey, rendered, handle) =>
        val normalised = normaliseSendKey(sendKey, aliases, handleKeys)
        val text = renderAdapter(at, persona, normalised, rendered, aliases, codec)
        handleKeys += (chatId, handle.messageId) -> normalised
        if batch == 0L then
          flush()
          b ++= text
        else pending += text
    }
    flush()
    b.toString
  end render

  private def renderAdapter(
      at: Instant,
      persona: String,
      sendKey: String,
      rendered: RenderedMessage,
      aliases: Aliases,
      codec: CallbackCodec
  ): String =
    val b = StringBuilder()
    b ++= s"${fmt(at)} $persona send/${kindOf(sendKey)} $sendKey\n"
    rendered.chunks.foreach(chunk => chunk.linesIterator.foreach(line => b ++= s"  $line\n"))
    if rendered.choiceMap.nonEmpty then
      b ++= "  choices: "
      b ++= rendered.choiceMap.map(entry => renderChoice(entry, aliases, codec)).mkString("; ")
      b ++= "\n"
    b.toString

  private def renderChoice(entry: ChoiceMapEntry, aliases: Aliases, codec: CallbackCodec): String =
    val target = codec.decode(entry.callback) match
      case Right(payload) =>
        val subject = aliases.forSubject(payload.subject)
        s"${payload.action.name}($subject,${payload.value})"
      case Left(_) => "?"
    s"${entry.index}) ${entry.label} -> $target"

  /** `occ:<uuid>:e1:s1:kinitial:c<uuid>` -> `occ:p1-2026-10-05-t0900:e1:s1:kinitial`; a finalize's
    * `:t<chat>:m<n>` target -> `-><the superseded send's normalised key>`; `digest:<account>:<bucket>` ->
    * `digest:<persona>:<bucket>`. Any other occurrence/account id is alias-replaced verbatim (deterministic event
    * ids stay as they are).
    */
  private def normaliseSendKey(
      sendKey: String,
      aliases: Aliases,
      handleKeys: mutable.HashMap[(String, String), String]
  ): String =
    if sendKey.startsWith("occ:") then
      val rest = sendKey.stripPrefix("occ:")
      val occurrenceId = UUID.fromString(rest.takeWhile(_ != ':'))
      val body = rest.drop(occurrenceId.toString.length + 1)
      val (core, target) =
        val finalizeAt = body.indexOf(":t")
        if body.contains(":kfinalize") && finalizeAt >= 0 then (body.take(finalizeAt), Some(body.drop(finalizeAt + 2)))
        else (body.replaceAll(":c[0-9a-f-]{36}$", ""), None)
      val alias = aliases.occurrence(occurrenceId)
      target match
        case Some(handle) =>
          // the target is `encodeHandle` = s"<chatId>:<messageId>"; chatId itself contains ':'
          val sep = handle.lastIndexOf(':')
          val key = (handle.take(sep), handle.drop(sep + 1))
          s"occ:$alias:$core->${handleKeys.getOrElse(key, handle)}"
        case None => s"occ:$alias:$core"
    else if sendKey.startsWith("digest:") then
      val rest = sendKey.stripPrefix("digest:")
      val accountId = UUID.fromString(rest.takeWhile(_ != ':'))
      s"digest:${aliases.persona(accountId)}:${rest.drop(accountId.toString.length + 1)}"
    else if sendKey.startsWith("wizard:") then
      val rest = sendKey.stripPrefix("wizard:")
      val sessionId = rest.takeWhile(_ != ':')
      s"wizard:${sessionAlias(sessionId)}:${rest.drop(sessionId.length + 1)}"
    else aliases.replaceIds(sendKey)

  private def sessionAlias(sessionId: String): String =
    sessionAliases.getOrElseUpdate(
      sessionId, {
        sessionCount += 1
        s"session-$sessionCount"
      }
    )

  private def kindOf(sendKey: String): String =
    if sendKey.startsWith("toast:") then "toast"
    else if sendKey.startsWith("digest:") then "digest"
    else if sendKey.startsWith("occ:") then
      val kind = sendKey.split(":k").drop(1).headOption.getOrElse("?").takeWhile(_ != ':')
      if kind.startsWith("finalize") then "finalize" else kind
    else "reply"

  private def fmt(at: Instant): String = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC).format(at)

/** One recorded adapter send, as the console user saw it (handle = the printed `[#n]` number). */
final case class RecordedMessage(
    chatId: String,
    sendKey: String,
    rendered: RenderedMessage,
    handle: MessageHandle
)

/** The stable alias maps the golden rendering needs: occurrence id -> `p1-2026-10-05-t0900`, account id -> persona.
  * Built from the database after the run (ids are random per run; the natural key is not).
  */
final class Aliases(occurrences: Map[UUID, String], accounts: Map[UUID, String]):

  def occurrence(id: UUID): String = occurrences.getOrElse(id, id.toString)

  def persona(id: UUID): String = accounts.getOrElse(id, id.toString)

  def forSubject(id: UUID): String = occurrences.get(id).orElse(accounts.get(id).map(p => s"$p")).getOrElse("-")

  def replaceIds(text: String): String =
    val uuid = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})".r
    uuid.replaceAllIn(text, m =>
      val id = UUID.fromString(m.group(1))
      occurrences.get(id).orElse(accounts.get(id)).getOrElse(m.group(1))
    )
