package dosegram.adapter.telegram

import dosegram.contracts.*
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys
import dosegram.core.chat.CallbackMode
import dosegram.core.chat.ActionRegistry
import dosegram.core.chat.ChatHandler
import dosegram.core.chat.ChatMediator
import dosegram.core.domain.copy.ReminderCopy
import dosegram.core.ports.Tx
import dosegram.tests.conformance.InMemoryUnitOfWork
import ox.supervised

import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** The adapter in front of the real `ChatMediator` on in-memory ports and the fake wire: what the user of a stale or
  * forged button sees, where a handler's toast ends up, and what a redelivered update does.
  */
class TelegramMediatorSuite extends munit.FunSuite:

  private final class Scripted(logic: InboundEvent => Reply) extends ChatHandler:
    val calls = AtomicInteger(0)
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      calls.incrementAndGet()
      logic(event)

  private final case class Wired(rig: TelegramRig, uow: InMemoryUnitOfWork, handler: Scripted)

  private def wired[A](logic: InboundEvent => Reply)(test: Wired => A): A =
    supervised:
      val rig = TelegramRig()
      val uow = InMemoryUnitOfWork()
      val handler = Scripted(logic)
      val mediator = ChatMediator(uow, Map(TelegramAdapter.Vendor -> rig.adapter), TelegramRig.codec, handler, rig.timer)
      rig.adapter.start(mediator, None)
      test(Wired(rig, uow, handler))

  private def await(clue: String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    while !condition && System.nanoTime() < deadline do Thread.sleep(5)
    assert(condition, clue)

  private def message(event: InboundEvent, text: String, replaces: Option[MessageHandle] = None): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      replaces = replaces,
      dedupeKey = s"test:${event.vendorEventId}",
      correlationId = s"test:${event.vendorEventId}"
    )

  test("a tap on a button that is not a token of ours is answered with the stale-button toast and changes nothing"):
    wired(_ => fail("nothing may reach the core")): w =>
      w.rig.deliverTap("dc:not-a-token")
      assertEquals(w.rig.answers.map(_.text("text")), List(Some(ReminderCopy.staleControlToast)))
      w.rig.timer.advance(Duration.ofSeconds(5))
      assertEquals(w.rig.answers.size, 1, "the watchdog must not answer a second time")
      assertEquals(w.rig.messageCalls, Nil)
      assertEquals(w.handler.calls.get(), 0)
      assertEquals(w.uow.audit.all.map(_.kind), List("callback_tampered"))

  test("a token signed with a key we do not hold is answered the same way"):
    val foreign = CallbackCodec(CallbackKeys(CallbackKey(1, Array.fill[Byte](16)(42)), None))
    val taken = ActionRegistry.byName("dose.taken").getOrElse(fail("dose.taken is registered"))
    val forged = foreign.encode(CallbackMode.Direct, taken, UUID.randomUUID(), 0).wire
    wired(_ => fail("nothing may reach the core")): w =>
      w.rig.deliverTap(forged)
      assertEquals(w.rig.answers.map(_.text("text")), List(Some(ReminderCopy.staleControlToast)))
      assertEquals(w.handler.calls.get(), 0)

  test("a handler's toast for a stale tap is the single answer: a toast, not a message in the chat"):
    wired(_ => Reply(toast = Some(ReminderCopy.alreadyRecorded("09:03")))): w =>
      w.rig.deliverTap(TelegramRig.token("dose.taken", UUID.randomUUID()))
      await("the toast was not answered")(w.rig.answers.nonEmpty)
      w.rig.timer.advance(Duration.ofSeconds(5))
      assertEquals(w.rig.answers.map(_.text("text")), List(Some("Already recorded at 09:03.")))
      assertEquals(w.rig.messageCalls, Nil)

  test("a tap whose handler replaces the card edits the tapped message and is answered once, without text"):
    val source = MessageHandle("telegram", "7001", "50")
    wired(event => Reply(replace = Some(message(event, "Recorded at 09:03.", Some(source))))): w =>
      w.rig.deliverTap(TelegramRig.token("dose.taken", UUID.randomUUID()))
      await("the card was not edited")(w.rig.messageCalls.nonEmpty)
      val edit = w.rig.messageCalls.head
      assertEquals(edit.method, "editMessageText")
      assertEquals(edit.number("message_id"), Some(50L))
      assertEquals(edit.text("text"), Some("Recorded at 09:03."))
      w.rig.timer.advance(Duration.ofSeconds(5))
      assertEquals(w.rig.answers.map(_.text("text")), List(None))

  test("the same update delivered twice runs the handler once; the mediator replays the stored reply"):
    wired(event => Reply(followUps = List(message(event, "Nothing is due right now.")))): w =>
      assert(w.rig.ingest("message-text"))
      assert(w.rig.ingest("message-text"))
      await("both deliveries were not answered")(w.rig.messageCalls.size == 2)
      assertEquals(w.handler.calls.get(), 1)
      assert(w.uow.inboundEvents.find("telegram", "telegram:update:900000001").isDefined)
      assertEquals(w.rig.messageCalls.map(_.text("text")).distinct, List(Some("Nothing is due right now.")))
