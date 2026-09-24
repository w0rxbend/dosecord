package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.application.CommandRegistry
import dosecord.core.application.FirstFlows
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.ChatMediator
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.ports.Clock
import dosecord.tests.conformance.InMemoryUnitOfWork
import dosecord.tests.conformance.RecordingSink
import dosecord.tests.conformance.WireEvent

import scala.collection.mutable.ListBuffer

/** ROADMAP M2.2 acceptance on the fake wire: the account-create and `/mood` flows run end to end through the real
  * [[DiscordAdapter]] on the fake transport — `/start` -> the timezone select -> the live-echo confirm -> the account
  * row, two `/mood` checkins, `/help` from the registry, and the refused second create. Every reply rides the live
  * interaction (the deferred hook), never a channel send (the M2.1-flagged resolution: post-interaction replies are
  * interaction responses, not channel messages). The live-DM half of this criterion is the owner's run (see the
  * handoff runbook); this is the fake-wire evidence over the same paths.
  */
class DiscordFirstFlowsSuite extends munit.FunSuite:

  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  private final class Rig(using ox.Ox):
    val recording = RecordingSink()
    val server = DiscordFakeVendorServer(recording)
    val uow = InMemoryUnitOfWork()
    val latencies = ListBuffer.empty[Duration]
    val clock: Clock = () => server.clock
    val adapter = DiscordAdapter(server.transport, (_, _) => Nil, latencies += _, () => server.clock, server.log)
    private val mediator = ChatMediator(
      uow,
      Map("discord" -> adapter),
      codec,
      FirstFlows.handler(uow, Map("discord" -> adapter), codec, clock),
      clock,
      commands = CommandRegistry.byName
    )
    adapter.start(TeeSink(recording, mediator), None)
    adapter.registerCommands(CommandRegistry.all)

    def stop(): Unit = adapter.stop()

    def deliver(event: WireEvent): Unit = server.deliver(event)

    def wireTexts: List[String] = server.transport.wireSnapshot.map(_.text)

    /** The custom id of the button labelled `label` on the newest message carrying it. */
    def buttonToken(label: String): String =
      server.transport.wireSnapshot.reverse
        .flatMap(_.components)
        .collect { case DiscordComponent.ButtonRow(buttons) => buttons }
        .flatten
        .find(_.label == label)
        .map(_.customId)
        .getOrElse(fail(s"no button '$label' on the observed messages"))

  private final class TeeSink(recording: RecordingSink, delegate: InboundSink) extends InboundSink:
    override def push(event: InboundEvent): Boolean =
      recording.push(event)
      delegate.push(event)

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    if !ok then throw AssertionError(clue)

  test("account-create and /mood run end to end on the fake wire"):
    ox.supervised:
      val rig = Rig()
      try
        // /start -> the timezone select menu.
        rig.deliver(WireEvent.Command("w-start", "dm-1", "start", "/start"))
        await(rig.wireTexts.exists(_.contains(IdentityCopy.timezonePrompt)), "the timezone picker")
        val zoneToken = rig.server.transport.wireSnapshot.reverse
          .flatMap(_.components)
          .collect { case DiscordComponent.Select(_, options, _, _) => options }
          .flatten
          .find(_.label.contains("Europe/Kyiv"))
          .map(_.value)
          .getOrElse(fail("no Europe/Kyiv option on the picker"))
        // The select submission routes by the chosen option's dc: token.
        rig.deliver(WireEvent.Select("w-tz", "dm-1", zoneToken, "m1"))
        await(rig.wireTexts.exists(_.contains("It is")), "the live-echo confirm")
        rig.deliver(WireEvent.Callback("w-yes", "dm-1", rig.buttonToken("Yes"), "m2"))
        await(rig.uow.accounts.all.size == 1, "the account row")
        assertEquals(rig.uow.accounts.all.head.currentTimezone, "Europe/Kyiv")
        assertEquals(rig.uow.accounts.all.head.displayName, None, "display name stays neutral (K8)")

        // /mood with note and tags, then a bare /mood.
        rig.deliver(WireEvent.Command("w-mood-1", "dm-1", "mood", "/mood 8 slept well #sleep"))
        await(rig.uow.moodCheckins.all.size == 1, "the first checkin")
        rig.deliver(WireEvent.Command("w-mood-2", "dm-1", "mood", "/mood 8"))
        await(rig.uow.moodCheckins.all.size == 2, "the second checkin")
        assertEquals(
          rig.uow.moodCheckins.all.map(r => (r.moodLevel.value, r.note, r.tags)),
          List((8, Some("slept well"), List("sleep")), (8, None, Nil))
        )

        // /help lists every registered command including itself (K9).
        rig.deliver(WireEvent.Command("w-help", "dm-1", "help", "/help"))
        await(rig.wireTexts.exists(_.contains("/help —")), "the help reply")
        CommandRegistry.all.foreach: spec =>
          assert(rig.wireTexts.exists(_.contains(s"/${spec.name}")), s"/help lists /${spec.name}")

        // The second create is refused with the catalogue error and writes nothing (R3).
        rig.deliver(WireEvent.Command("w-start-2", "dm-1", "start", "/start"))
        await(rig.wireTexts.exists(_.contains(IdentityCopy.alreadyLinked)), "the already-linked refusal")
        assertEquals(rig.uow.accounts.all.size, 1, "still one account")
        assertEquals(rig.uow.sessions.all, Nil, "no leftover sessions")

        // The domain events carry the created account id.
        assertEquals(
          rig.uow.domainEvents.all.map(_.eventType),
          List(Event.AccountCreatedType, Event.MoodCheckinRecordedType, Event.MoodCheckinRecordedType)
        )
        rig.uow.domainEvents.all.foreach: e =>
          assertEquals(e.accountId, Some(rig.uow.accounts.all.head.accountId), s"${e.eventType} account_id")

        // The M2.1-flagged resolution: every reply rode the live interaction (the deferred hook); no channel sends.
        assertEquals(rig.server.transport.sentSnapshot, Nil, "no reply was sent as a channel message")
      finally rig.stop()
end DiscordFirstFlowsSuite
