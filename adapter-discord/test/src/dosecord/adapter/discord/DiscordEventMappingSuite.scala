package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.ActionRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CallbackMode
import dosecord.core.domain.copy.AdapterCopy
import dosecord.tests.conformance.RecordingSink

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer

/** Adapter-level behaviour on the fake wire (ROADMAP M2.1 acceptance): the inbound event mapping
  * (`discord:interaction:{id}` / `discord:message:{id}`), the ephemeral non-DM refusal, the seeded autocomplete round
  * trip (the hard requirement deferred from M0.8), the 1.5 s ack watchdog from the snowflake (declared type, disabled
  * for opensForm), the ack-latency sample, `AdapterLifecycle` on reconnect, the `dm_channel_id` cache and 50007 ->
  * `Unreachable` — all without live Discord.
  */
class DiscordEventMappingSuite extends munit.FunSuite:

  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  private final class Rig(val autocomplete: (PlatformIdentity, String) => List[String] = (_, _) => Nil):
    val sink = RecordingSink()
    val server = DiscordFakeVendorServer(sink)
    val latencies = ListBuffer.empty[Duration]
    val adapter = DiscordAdapter(server.transport, autocomplete, latencies += _, () => server.clock, server.log)
    adapter.start(sink, None)
    private val seq = AtomicLong(0)

    def nextId(): String = Snowflake.mint(server.clock, seq.incrementAndGet().toInt)

    def interaction(id: String, context: DiscordContext = DiscordContext.BotDm, channelId: String = "dm-1") =
      FakeDiscordInteraction(server.transport, id, DiscordUser("user-1", "owner"), channelId, context)

    def deliver(event: DiscordEvent): Unit = server.transport.deliver(event)

    def awaitInbound(before: Int): InboundEvent =
      await(sink.all.size > before, "no inbound event")
      sink.all.last

    def await(cond: => Boolean, clue: String): Unit =
      val deadline = System.nanoTime() + 10_000_000_000L
      var ok = cond
      while !ok && System.nanoTime() < deadline do
        Thread.sleep(10)
        ok = cond
      if !ok then throw AssertionError(clue)

  private def tokenFor(action: String, subject: UUID, value: Long): String =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value).wire

  test("a slash command maps to CommandInvoked with typed args, the raw line and discord:interaction:{id}"):
    val rig = Rig()
    try
      val id = rig.nextId()
      val before = rig.sink.all.size
      rig.deliver(
        DiscordEvent.SlashCommand(
          rig.interaction(id),
          "mood",
          List(DiscordOption("level", "8"), DiscordOption("note", "slept well"))
        )
      )
      val event = rig.awaitInbound(before)
      assertEquals(event.vendorEventId, s"discord:interaction:$id")
      assertEquals(event.createdAt, Some(Snowflake.createdAt(id)))
      assertEquals(event.actor, PlatformIdentity("discord", "user-1", Some("owner")))
      assertEquals(event.chat, ChatRef("discord", "dm-1"))
      assertEquals(event.cursor, Some(id))
      assert(event.interaction.isDefined)
      event.body match
        case Inbound.CommandInvoked(name, args, raw) =>
          assertEquals(name, "mood")
          assertEquals(args("level"), "8")
          assertEquals(args("note"), "slept well")
          assertEquals(raw, "/mood 8 slept well")
        case other => fail(s"expected CommandInvoked, got $other")
    finally rig.adapter.stop()

  test("a button maps to InteractionSubmitted with the token's decoded fields and source message"):
    val rig = Rig()
    try
      val subject = UUID.randomUUID()
      val token = tokenFor("dose.taken", subject, 0)
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), token, Nil, "m9"))
      val event = rig.awaitInbound(before)
      event.body match
        case Inbound.InteractionSubmitted(ref, _, source) =>
          assertEquals(ref.actionId, 1)
          assertEquals(ref.subject, subject)
          assertEquals(ref.value, 0L)
          assertEquals(ref.raw, token)
          assertEquals(source, Some(MessageHandle("discord", "dm-1", "m9")))
        case other => fail(s"expected InteractionSubmitted, got $other")
    finally rig.adapter.stop()

  test("a select menu resolves the callback from the selected option values"):
    val rig = Rig()
    try
      val token = tokenFor("dose.snooze", UUID.randomUUID(), 10)
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), "snooze-menu", List(token), "m9"))
      val event = rig.awaitInbound(before)
      event.body match
        case Inbound.InteractionSubmitted(ref, values, _) =>
          assertEquals(ref.raw, token)
          assertEquals(values, List(token))
        case other => fail(s"expected InteractionSubmitted, got $other")
    finally rig.adapter.stop()

  test("a modal submit maps to FormSubmitted with the form id, the submit token and the fields"):
    val rig = Rig()
    try
      val token = tokenFor("wizard.text_step", UUID.randomUUID(), 3)
      val before = rig.sink.all.size
      rig.deliver(
        DiscordEvent.ModalSubmit(rig.interaction(rig.nextId()), s"add_medication:$token", Map("name" -> "Vitamin D"))
      )
      val event = rig.awaitInbound(before)
      event.body match
        case Inbound.FormSubmitted(formId, ref, fields) =>
          assertEquals(formId, "add_medication")
          assertEquals(ref.raw, token)
          assertEquals(fields, Map("name" -> "Vitamin D"))
        case other => fail(s"expected FormSubmitted, got $other")
    finally rig.adapter.stop()

  test("a DM message maps to MessageReceived with the reply target and discord:message:{id}"):
    val rig = Rig()
    try
      val id = rig.nextId()
      val before = rig.sink.all.size
      rig.deliver(
        DiscordEvent.Message(id, Snowflake.createdAt(id), DiscordUser("user-1", "owner"), "dm-1", "hello", Some("m7"))
      )
      val event = rig.awaitInbound(before)
      assertEquals(event.vendorEventId, s"discord:message:$id")
      event.body match
        case Inbound.MessageReceived(text, replyTo, _) =>
          assertEquals(text, "hello")
          assertEquals(replyTo, Some(MessageHandle("discord", "dm-1", "m7")))
        case other => fail(s"expected MessageReceived, got $other")
    finally rig.adapter.stop()

  test("the bot's own messages are never inbound"):
    val rig = Rig()
    try
      rig.deliver(
        DiscordEvent.Message(rig.nextId(), rig.server.clock, DiscordUser("bot-1", "dosecord"), "dm-1", "hi", None)
      )
      Thread.sleep(100)
      assert(rig.sink.all.isEmpty)
    finally rig.adapter.stop()

  test("a guild-context slash is refused with the ephemeral DM-only redirect and never reaches the core"):
    val rig = Rig()
    try
      rig.deliver(
        DiscordEvent.SlashCommand(
          rig.interaction(rig.nextId(), context = DiscordContext.Guild, channelId = "guild-1"),
          "mood",
          List(DiscordOption("level", "8"))
        )
      )
      rig.await(rig.server.transport.interactionReplies.nonEmpty, "no ephemeral redirect")
      assert(rig.sink.all.isEmpty)
      val (_, message, ephemeral) = rig.server.transport.interactionReplies.head
      assertEquals(message.text, AdapterCopy.dmOnlyRedirect)
      assert(ephemeral, "the redirect must be visible only to the sender")
    finally rig.adapter.stop()

  test("a group-DM (PRIVATE_CHANNEL) interaction is refused the same way"):
    val rig = Rig()
    try
      rig.deliver(
        DiscordEvent.Component(
          rig.interaction(rig.nextId(), context = DiscordContext.PrivateChannel, channelId = "group-1"),
          "dc:whatever",
          Nil,
          "m1"
        )
      )
      rig.await(rig.server.transport.interactionReplies.nonEmpty, "no ephemeral redirect")
      assert(rig.sink.all.isEmpty)
      assertEquals(rig.server.transport.interactionReplies.head._2.text, AdapterCopy.dmOnlyRedirect)
    finally rig.adapter.stop()

  test("an autocomplete request returns the seeded medication names"):
    val rig = Rig((_, query) => if query.startsWith("vit") then List("Vitamin D", "Vitamin K") else Nil)
    try
      rig.deliver(
        DiscordEvent.Autocomplete(rig.interaction(rig.nextId()), "log", "medication", "vit")
      )
      rig.await(rig.server.transport.autocompleteReplies.nonEmpty, "no autocomplete reply")
      assertEquals(rig.server.transport.autocompleteReplies.last, List("Vitamin D", "Vitamin K"))
      assert(rig.sink.all.isEmpty, "autocomplete is answered by the adapter, not dispatched to the core")
    finally rig.adapter.stop()

  test("the watchdog auto-acks an un-acked component with deferUpdate at 1.5 s from the snowflake"):
    val rig = Rig()
    try
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), "dc:not-a-real-token", Nil, "m9"))
      rig.awaitInbound(before)
      Thread.sleep(150)
      assert(rig.server.transport.acks.isEmpty, "no ack before the deadline")
      rig.server.advanceClock(Duration.ofSeconds(2))
      rig.await(rig.server.transport.acks.nonEmpty, "no watchdog ack after the deadline")
      assertEquals(rig.server.transport.acks.head.kind, "deferUpdate")
      assert(rig.sink.all.last.interaction.exists(_.acked))
    finally rig.adapter.stop()

  test("the watchdog is disabled for opensForm actions"):
    val rig = Rig()
    try
      val token = tokenFor("wizard.text_step", UUID.randomUUID(), 1)
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), token, Nil, "m9"))
      rig.awaitInbound(before)
      rig.server.advanceClock(Duration.ofSeconds(5))
      assert(rig.server.transport.acks.isEmpty, "opensForm interactions must not be auto-acked")
    finally rig.adapter.stop()

  test("the watchdog defers a reply for slash commands with the declared visibility"):
    val rig = Rig()
    try
      rig.adapter.registerCommands(CommandRegistry.all)
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.SlashCommand(rig.interaction(rig.nextId()), "today", Nil))
      rig.awaitInbound(before)
      rig.server.advanceClock(Duration.ofSeconds(2))
      rig.await(rig.server.transport.acks.nonEmpty, "no watchdog ack")
      assertEquals(rig.server.transport.acks.head.kind, "deferReply")
    finally rig.adapter.stop()

  test("the watchdog defers an update for modal submits"):
    val rig = Rig()
    try
      val token = tokenFor("wizard.text_step", UUID.randomUUID(), 3)
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.ModalSubmit(rig.interaction(rig.nextId()), s"add_medication:$token", Map("name" -> "x")))
      rig.awaitInbound(before)
      rig.server.advanceClock(Duration.ofSeconds(2))
      rig.await(rig.server.transport.acks.nonEmpty, "no watchdog ack")
      assertEquals(rig.server.transport.acks.head.kind, "deferUpdate")
    finally rig.adapter.stop()

  test("ack latency is recorded from the snowflake at the moment of the ack"):
    val rig = Rig()
    try
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), "dc:not-a-real-token", Nil, "m9"))
      rig.awaitInbound(before)
      rig.server.advanceClock(Duration.ofSeconds(2))
      rig.await(rig.latencies.nonEmpty, "no ack latency sample")
      assertEquals(rig.latencies.last, Duration.ofMillis(2000))
    finally rig.adapter.stop()

  test("a reconnect emits AdapterLifecycle carrying the last seen cursor"):
    val rig = Rig()
    try
      val messageId = rig.nextId()
      val before = rig.sink.all.size
      rig.deliver(
        DiscordEvent.Message(messageId, Snowflake.createdAt(messageId), DiscordUser("user-1", "owner"), "dm-1", "hi", None)
      )
      rig.awaitInbound(before)
      val size = rig.sink.all.size
      rig.deliver(DiscordEvent.Lifecycle(LifecycleState.Resumed, None))
      val event = rig.awaitInbound(size)
      assertEquals(event.vendorEventId, "discord:lifecycle:1")
      event.body match
        case Inbound.AdapterLifecycle(state, fromCursor) =>
          assertEquals(state, LifecycleState.Resumed)
          assertEquals(fromCursor, Some(messageId))
        case other => fail(s"expected AdapterLifecycle, got $other")
    finally rig.adapter.stop()

  test("a closed or blocked DM (50007) surfaces as Unreachable with channelFatal"):
    val rig = Rig()
    try
      rig.server.transport.failNext(dosecord.tests.conformance.VendorFault.Blocked)
      val error = intercept[ChatError.Unreachable]:
        rig.adapter.send(ChatRef("discord", "dm-1"), RenderedMessage(chunks = List("hi")), "k1")
      assert(error.channelFatal)
    finally rig.adapter.stop()

  test("the dm_channel_id cache answers resolveChat without opening a channel; the cold path opens once"):
    val rig = Rig()
    try
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.SlashCommand(rig.interaction(rig.nextId(), channelId = "dm-42"), "today", Nil))
      rig.awaitInbound(before)
      assertEquals(
        rig.adapter.resolveChat(PlatformIdentity("discord", "user-1")),
        ChatRef("discord", "dm-42")
      )
      assert(rig.server.transport.openChannelCalls.isEmpty, "a cached DM channel must not re-open the channel")
      assertEquals(
        rig.adapter.resolveChat(PlatformIdentity("discord", "user-2")),
        ChatRef("discord", "dm-user-2")
      )
      assertEquals(rig.server.transport.openChannelCalls.toList, List("user-2"))
      // ... and the opened channel is cached from then on.
      rig.adapter.resolveChat(PlatformIdentity("discord", "user-2"))
      assertEquals(rig.server.transport.openChannelCalls.toList, List("user-2"))
    finally rig.adapter.stop()

  test("the handle answers through the deferred hook once acked"):
    val rig = Rig()
    try
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), "dc:not-a-real-token", Nil, "m9"))
      val event = rig.awaitInbound(before)
      val handle = event.interaction.get
      assert(!handle.acked)
      handle.deferReply(ephemeral = false)
      assert(handle.acked)
      assertEquals(rig.server.transport.acks.map(_.kind).last, "deferReply")
      val responded = handle.respond(RenderedMessage(chunks = List("recorded")))
      assertEquals(responded.chatId, "dm-1")
      assert(responded.messageId.nonEmpty)
    finally rig.adapter.stop()

  test("an undecodable token still reaches the mediator for the MAC rejection"):
    val rig = Rig()
    try
      val before = rig.sink.all.size
      rig.deliver(DiscordEvent.Component(rig.interaction(rig.nextId()), "dc:garbage", Nil, "m9"))
      val event = rig.awaitInbound(before)
      event.body match
        case Inbound.InteractionSubmitted(ref, _, _) =>
          assertEquals(ref.actionId, 0)
          assertEquals(ref.raw, "dc:garbage")
        case other => fail(s"expected InteractionSubmitted, got $other")
    finally rig.adapter.stop()
end DiscordEventMappingSuite
