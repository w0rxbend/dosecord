package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.application.Application
import dosecord.core.application.CommandRegistry
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.ChatMediator
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.MenuCopy
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.Clock
import dosecord.tests.conformance.InMemoryUnitOfWork
import dosecord.tests.conformance.RecordingSink
import dosecord.tests.conformance.WireEvent

import java.time.Duration
import scala.collection.mutable.ListBuffer

/** ROADMAP M2.2 acceptance on the fake wire: a text step opens one modal inside the 1.5 s ack budget with the fake
  * clock (the modal IS the ack, the watchdog is disabled for `opensForm`), and a modal opened before a restart still
  * submits — the modal's custom id routes through the `dc:` prefix with no view registration, so a brand-new adapter
  * instance over the same mediator accepts the submission and the wizard advances. The full add-medication wizard runs
  * through the real [[Application]] composition on the real [[DiscordAdapter]] against the fake transport.
  */
class DiscordComponentsSuite extends munit.FunSuite:

  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** The real mediator + M1.9 composition over the in-memory ports; the adapter under test is swappable so a test can
    * restart it (a fresh instance over the same transport, sink and mediator).
    */
  private final class Rig(using ox.Ox):
    val recording = RecordingSink()
    val server = DiscordFakeVendorServer(recording)
    val uow = InMemoryUnitOfWork()
    val latencies = ListBuffer.empty[Duration]
    val clock: Clock = () => server.clock

    private var adapterInstance = newAdapter()
    private def newAdapter(): DiscordAdapter =
      val adapter = DiscordAdapter(server.transport, (_, _) => Nil, latencies += _, () => server.clock, server.log)
      adapter.registerCommands(CommandRegistry.all)
      adapter

    private val mediator = ChatMediator(
      uow,
      Map("discord" -> adapterInstance),
      codec,
      Application.handler(uow, Map("discord" -> adapterInstance), codec, clock),
      clock,
      commands = CommandRegistry.byName
    )

    adapterInstance.start(TeeSink(recording, mediator), None)

    /** Stops the adapter and starts a brand-new instance over the same transport, sink and mediator (the restart). */
    def restart(): Unit =
      adapterInstance.stop()
      adapterInstance = newAdapter()
      adapterInstance.start(TeeSink(recording, mediator), None)

    def stop(): Unit = adapterInstance.stop()

    def deliver(event: WireEvent): Unit = server.deliver(event)

    /** The newest message the wire observed. */
    def lastMessage: DiscordMessage =
      server.transport.wireSnapshot.lastOption.getOrElse(fail("no message observed"))

    /** The custom id of the button labelled `label` on the newest message carrying it. */
    def buttonToken(label: String): String =
      server.transport.wireSnapshot.reverse
        .flatMap(_.components)
        .collect { case DiscordComponent.ButtonRow(buttons) => buttons }
        .flatten
        .find(_.label == label)
        .map(_.customId)
        .getOrElse(fail(s"no button '$label' on the observed messages"))

    /** Creates the account (`/start` -> timezone picker -> [Yes]); every later flow is account-gated. */
    def bootstrap(): Unit =
      deliver(WireEvent.Command("w-start", "dm-1", "start", "/start"))
      await(server.transport.wireSnapshot.nonEmpty, "the timezone picker")
      val zoneToken = server.transport.wireSnapshot.reverse
        .flatMap(_.components)
        .collect { case DiscordComponent.Select(_, options, _, _) => options }
        .flatten
        .find(_.label.contains("Europe/Kyiv"))
        .map(_.value)
        .getOrElse(fail("no Europe/Kyiv option on the picker"))
      deliver(WireEvent.Select("w-tz", "dm-1", zoneToken, "m0"))
      await(server.transport.wireSnapshot.exists(_.text.contains("It is")), "the live-echo confirm")
      deliver(WireEvent.Callback("w-yes", "dm-1", buttonToken(Labels.Yes), "m0b"))
      await(uow.accounts.all.size == 1, "the account row")

    /** Drives `/menu` -> Medications -> Add medication; the modal opens. Returns the modal's custom id. */
    def driveToModal(): String =
      bootstrap()
      deliver(WireEvent.Command("w-menu", "dm-1", "menu", "/menu"))
      await(server.transport.wireSnapshot.exists(_.text.contains(MenuCopy.mainPrompt)), "the menu prompt")
      deliver(WireEvent.Callback("w-meds", "dm-1", buttonToken(MenuCopy.topLevel(1)), "m1"))
      await(server.transport.wireSnapshot.exists(_.text.contains(MenuCopy.medicationsPrompt)), "the submenu prompt")
      deliver(WireEvent.Callback("w-add", "dm-1", buttonToken(MenuCopy.medicationsAdd), "m2"))
      await(server.transport.modalSnapshot.nonEmpty, "the modal opened")
      val payload = server.transport.modalSnapshot.last
      payload.substring("custom_id=".length, payload.indexOf(';'))
  end Rig

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

  private def awaitMessageContaining(rig: Rig)(text: String): Unit =
    await(rig.server.transport.wireSnapshot.exists(_.text.contains(text)), s"no message containing '$text'")

  test("a text step opens one modal inside the 1.5 s budget with a fake clock"):
    ox.supervised:
      val rig = Rig()
      try
        val customId = rig.driveToModal()
        assert(customId.startsWith("medication.add:"), s"the modal custom id is '<form id>:<submit token>': $customId")
        assert(customId.contains(":dc:"), s"the submit token carries the dc: prefix: $customId")
        // The modal IS the ack of the opensForm interaction (M0.8): no defer landed on it before the modal.
        assertEquals(rig.server.transport.ackSnapshot.map(_.kind).toList.last, "replyModal")
        assertEquals(rig.server.transport.ackSnapshot.count(_.kind == "replyModal"), 1)
        // The ack latency is measured from the snowflake; with the fake clock it lands well inside the 1.5 s budget.
        assert(rig.latencies.nonEmpty, "the modal ack recorded a latency sample")
        assert(
          rig.latencies.last.compareTo(DiscordAdapter.WatchdogTarget) <= 0,
          s"modal ack ${rig.latencies.last} exceeds the budget"
        )
        // One modal for the whole text step: the form's four fields in a single payload.
        val payload = rig.server.transport.modalSnapshot.last
        assert(payload.contains("fields=name,dose,times,instructions"), s"one modal with all four fields: $payload")
      finally rig.stop()

  test("a modal opened before a restart still submits and routes through dc:"):
    ox.supervised:
      val rig = Rig()
      try
        val customId = rig.driveToModal()
        // The process "restarts": a brand-new adapter instance over the same transport, sink and mediator.
        rig.restart()
        // The modal's custom id needs no registration anywhere: the new instance routes it.
        rig.deliver(
          WireEvent.ModalSubmit("w-submit", "dm-1", customId, Map("name" -> "Vitamin D", "dose" -> "1000 IU", "times" -> "09:00", "instructions" -> ""), "m3")
        )
        awaitMessageContaining(rig)(WizardCopy.daysPrompt)
        // The wizard advanced: the session sits on the days step with the modal's answers recorded.
        assertEquals(rig.uow.sessions.all.size, 1)
        assertEquals(rig.uow.sessions.all.head.step, "days")
        // Finish the wizard: [Every day] -> [Create] -> one medication row.
        rig.deliver(WireEvent.Callback("w-days", "dm-1", rig.buttonToken(WizardCopy.everyDay), "m4"))
        awaitMessageContaining(rig)("every day 09:00")
        rig.deliver(WireEvent.Callback("w-create", "dm-1", rig.buttonToken(Labels.Create), "m5"))
        await(rig.uow.medications.all.size == 1, "the medication row")
        assertEquals(rig.uow.medications.all.head.name, "Vitamin D")
      finally rig.stop()
end DiscordComponentsSuite
