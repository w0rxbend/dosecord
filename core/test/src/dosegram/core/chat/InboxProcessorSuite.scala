package dosegram.core.chat

import dosegram.core.ports.Clock
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxPurge
import dosegram.core.ports.PendingInboxEntry

import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** ROADMAP G1.7, ARCHITECTURE TG-06, TG-08, TG-09: the inbox processor over an in-memory inbox. */
class InboxProcessorSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val clock: Clock = () => t0

  /** The inbox as the database keeps it, without the database. */
  private final class MemoryInbox extends InboundInbox:
    private val rows = mutable.LinkedHashMap.empty[String, (InboxEntry, String, Int)]
    @volatile var failMark = false
    @volatile var failMarkOf: Option[String] = None

    def status(sequence: Long): String = synchronized(rows(s"u$sequence")._2)

    override def store(entry: InboxEntry, receivedAt: Instant): Unit = synchronized:
      if !rows.contains(entry.vendorEventId) then rows(entry.vendorEventId) = (entry, "pending", 0)

    override def pending(perChat: Int, limit: Int): List[PendingInboxEntry] = synchronized:
      rows.values
        .collect { case (entry, "pending", _) => entry }
        .toList
        .groupBy(_.chatKey)
        .values
        .flatMap(_.sortBy(_.sequence).take(perChat))
        .take(limit)
        .map(PendingInboxEntry(_, t0))
        .toList

    override def markProcessed(entry: InboxEntry, at: Instant): Unit = synchronized:
      if failMark || failMarkOf.contains(entry.vendorEventId) then
        throw SQLException("injected: the process died before the mark")
      rows(entry.vendorEventId) = rows(entry.vendorEventId).copy(_2 = "processed")

    override def recordFailure(entry: InboxEntry, maxAttempts: Int, at: Instant): Boolean = synchronized:
      val (stored, _, attempts) = rows(entry.vendorEventId)
      val quarantined = attempts + 1 >= maxAttempts
      rows(entry.vendorEventId) = (stored, if quarantined then "quarantined" else "pending", attempts + 1)
      quarantined

    override def purge(now: Instant): InboxPurge = InboxPurge(0, 0, 0)

  /** Sequences are unique across chats, like Telegram's `update_id`. */
  private def entry(sequence: Long, chat: String = "7001"): InboxEntry =
    InboxEntry("fake", s"u$sequence", chat, sequence, s"body-$sequence")

  private final class Rig(inbox: MemoryInbox = MemoryInbox()):
    val store: MemoryInbox = inbox
    val delivered = ConcurrentLinkedQueue[InboxEntry]()
    @volatile var refuse: InboxEntry => Boolean = _ => false
    var quarantined = 0
    val processor = InboxProcessor(
      inbox,
      pending =>
        if refuse(pending.entry) then false
        else
          delivered.add(pending.entry)
          true
      ,
      clock,
      log = _ => (),
      quarantined = () => quarantined += 1
    )

    def sequences(chat: String = "7001"): List[Long] =
      delivered.asScala.toList.filter(_.chatKey == chat).map(_.sequence)

  test("TG-06: the updates of one chat are delivered in sequence order, whatever order they were stored in"):
    val rig = Rig()
    List(entry(3), entry(11, "8002"), entry(1), entry(2), entry(12, "8002")).foreach(rig.store.store(_, t0))
    while rig.processor.processOnce() > 0 do ()
    assertEquals(rig.sequences(), List(1L, 2L, 3L))
    assertEquals(rig.sequences("8002"), List(11L, 12L))

  test("an entry is delivered once: a processed entry is not offered again"):
    val rig = Rig()
    rig.store.store(entry(1), t0)
    assertEquals(rig.processor.processOnce(), 1)
    assertEquals(rig.processor.processOnce(), 0)
    assertEquals(rig.sequences(), List(1L))
    assertEquals(rig.store.status(1), "processed")

  test("a failed entry holds back the later ones of its chat, and other chats go on"):
    val rig = Rig()
    List(entry(1), entry(2), entry(11, "8002")).foreach(rig.store.store(_, t0))
    rig.refuse = candidate => candidate == entry(1)
    rig.processor.processOnce()
    assertEquals(rig.sequences(), Nil)
    assertEquals(rig.sequences("8002"), List(11L))
    rig.refuse = _ => false
    while rig.processor.processOnce() > 0 do ()
    assertEquals(rig.sequences(), List(1L, 2L))

  test("TG-09: an entry that fails three times is quarantined, counted and skipped"):
    val rig = Rig()
    List(entry(1), entry(2)).foreach(rig.store.store(_, t0))
    rig.refuse = candidate => candidate == entry(1)
    rig.processor.processOnce()
    rig.processor.processOnce()
    assertEquals(rig.store.status(1), "pending")
    rig.processor.processOnce()
    assertEquals(rig.store.status(1), "quarantined")
    assertEquals(rig.quarantined, 1)
    while rig.processor.processOnce() > 0 do ()
    assertEquals(rig.sequences(), List(2L))

  test("a delivery that throws counts as a failed attempt and does not stop the processor"):
    val rig = Rig()
    rig.store.store(entry(1), t0)
    rig.refuse = _ => throw IllegalStateException("a handler failed")
    assertEquals(rig.processor.processOnce(), 0)
    rig.refuse = _ => false
    assertEquals(rig.processor.processOnce(), 1)

  test("TG-08: a crash between the delivery and the mark offers the entry again after a restart"):
    val inbox = MemoryInbox()
    inbox.store(entry(1), t0)
    val crashed = Rig(inbox)
    inbox.failMark = true
    assertEquals(crashed.processor.processOnce(), 0)
    assertEquals(inbox.status(1), "pending", "the mark was lost")
    inbox.failMark = false
    val restarted = Rig(inbox)
    assertEquals(restarted.processor.processOnce(), 1)
    assertEquals(restarted.sequences(), List(1L), "the receiver's own key makes the second delivery harmless")
    assertEquals(inbox.status(1), "processed")

  test("a failed inbox write ends its own chat's pass and leaves the other chats alone"):
    val inbox = MemoryInbox()
    List(entry(1), entry(11, "8002")).foreach(inbox.store(_, t0))
    inbox.failMarkOf = Some("u1")
    val rig = Rig(inbox)
    assertEquals(rig.processor.processOnce(), 1)
    assertEquals((inbox.status(1), inbox.status(11)), ("pending", "processed"))

  test("run processes what is stored, waits for a wake, and returns when stopped"):
    val rig = Rig()
    rig.store.store(entry(1), t0)
    val runner = Thread.ofVirtual().start(() => rig.processor.run())
    def waitFor(condition: => Boolean): Unit =
      val deadline = System.nanoTime() + 10_000_000_000L
      while !condition && System.nanoTime() < deadline do Thread.sleep(5)
      assert(condition)
    waitFor(rig.sequences() == List(1L))
    rig.store.store(entry(2), t0)
    rig.processor.wake()
    waitFor(rig.sequences() == List(1L, 2L))
    rig.processor.stop()
    runner.join(10_000)
    assert(!runner.isAlive, "run returned after stop")
