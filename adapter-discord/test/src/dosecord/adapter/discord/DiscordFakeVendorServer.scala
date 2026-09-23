package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.application.CommandRegistry
import dosecord.tests.conformance.*

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable

/** The Discord fake vendor wire (suite A): converts the shared wire events into normalized [[DiscordEvent]]s — snowflake
  * ids minted from the fake clock (stable per `wireId`, so a redelivery maps to the same `vendor_event_id`) — and
  * awaits the adapter's forked push to the sink. The adapter's clock is this server's clock, so the ack watchdog's
  * 1.5 s deadline is driven by `advanceClock`.
  */
final class DiscordFakeVendorServer(sink: RecordingSink) extends FakeVendorServer:

  val transport = FakeDiscordTransport(this)

  private val deliveriesBuf = mutable.ListBuffer.empty[(WireEvent, InboundEvent)]
  private val wireIds = mutable.HashMap.empty[String, String]
  private val seq = AtomicLong(0)
  private val logsBuf = mutable.ListBuffer.empty[String]
  @volatile private var at = Instant.parse("2026-09-21T00:00:00Z")

  def now: Instant = at

  /** The adapter's log hook: captured so log hygiene is honestly checked (no bodies, no tokens, no credentials). */
  def log(line: String): Unit = logsBuf += line

  override def deliver(event: WireEvent): Unit =
    val before = sink.all.size
    event match
      case WireEvent.Message(_, chat, text, replyTo) =>
        transport.deliver(DiscordEvent.Message(snowflakeOf(event), at, user, chat, text, replyTo))
      case WireEvent.Command(_, chat, name, text) =>
        transport.deliver(DiscordEvent.SlashCommand(interaction(event, chat), name, optionsOf(name, text)))
      case WireEvent.Callback(_, chat, callback, sourceMessageId) =>
        transport.deliver(DiscordEvent.Component(interaction(event, chat), callback, Nil, sourceMessageId))
    await(sink.all.size > before, s"no inbound event for $event")
    deliveriesBuf += ((event, sink.all.last))

  override def deliveries: List[(WireEvent, InboundEvent)] = deliveriesBuf.toList

  override def sent: List[SentObservation] = transport.sent.toList

  override def acks: List[AckObservation] = transport.acks.toList

  override def modalPayloads: List[String] = transport.modalPayloads.toList

  override def failNext(fault: VendorFault): Unit = transport.failNext(fault)

  override def clock: Instant = at

  /** Moves the fake clock and gives the watchdog's poll loop (<= 100 ms) a chance to observe the jump. */
  override def advanceClock(by: Duration): Unit =
    at = at.plus(by)
    Thread.sleep(250)

  override def logs: List[String] = logsBuf.toList

  private def user = DiscordUser("user-1", "owner")

  private def interaction(event: WireEvent, chat: String): FakeDiscordInteraction =
    FakeDiscordInteraction(transport, snowflakeOf(event), user, chat, DiscordContext.BotDm)

  /** Snowflakes are stable per `wireId`: a redelivered wire event maps to the same `discord:interaction:{id}`. */
  private def snowflakeOf(event: WireEvent): String =
    wireIds.getOrElseUpdate(event.wireKey, Snowflake.mint(at, seq.incrementAndGet().toInt))

  /** Splits a scenario's raw command text into typed options using the real registry's arg declarations: required args
    * take one token each, the last declared arg takes the remainder — so the adapter's reconstructed raw round-trips.
    */
  private def optionsOf(name: String, text: String): List[DiscordOption] =
    val tokens = text.trim.drop(1).dropWhile(c => !c.isWhitespace).trim.split("\\s+").toList.filter(_.nonEmpty)
    CommandRegistry.byName.get(name) match
      case None => tokens.map(token => DiscordOption("text", token))
      case Some(spec) =>
        val (required, optional) = spec.args.span(_.required)
        val requiredOpts = required.zip(tokens).map((arg, token) => DiscordOption(arg.name, token))
        val rest = tokens.drop(required.size)
        val optionalOpts =
          if optional.isEmpty then Nil
          else
            val init = optional.dropRight(1)
            val last = optional.last
            init.zip(rest).map((arg, token) => DiscordOption(arg.name, token)) ++
              Option.when(rest.size > init.size)(DiscordOption(last.name, rest.drop(init.size).mkString(" ")))
        requiredOpts ++ optionalOpts

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    if !ok then throw AssertionError(clue)
