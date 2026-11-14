package dosegram.core.application.reports

import dosegram.contracts.AttachmentRef
import dosegram.contracts.ChatRef
import dosegram.contracts.MediaKind
import dosegram.contracts.OutboundMessage
import dosegram.core.domain.copy.ImageCopy
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardPage
import dosegram.core.ports.ReportStatus

import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** ROADMAP G4.3 on the worker's side: a claimed request is drawn outside every transaction and its messages go to the
  * outbox with the images, or with their text equivalents when a page cannot be drawn or the request is late.
  */
class ReportWorkerSuite extends munit.FunSuite:
  import ReportFixtures.*

  /** Draws every page as `asset:<svg>` unless its SVG is in `failing`; records what it drew and for which chat. */
  private final class FakeDrawing(failing: Set[String] = Set.empty) extends CardDrawing:
    val drawn = ConcurrentLinkedQueue[(String, Option[ChatRef])]()
    override def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
      drawn.add(page.svg -> chat)
      if failing(page.svg) then Left("TimedOut")
      else Right(AttachmentRef(MediaKind.Photo, s"asset:${page.svg}", "image/png", page.altText, Some(page.fileName)))

  private final class Fallbacks:
    val templates = ConcurrentLinkedQueue[String]()

  private def worker(world: ReportWorld, drawing: CardDrawing, log: String => Unit = _ => (), fallbacks: Fallbacks = Fallbacks()) =
    ReportWorker(world.uow, drawing, world.clock, world.ids, log, template => fallbacks.templates.add(template): Unit)

  private def queue(world: ReportWorld, pages: Int*): Unit =
    world.uow.transaction(tx => CardDelivery(world.clock, world.ids).answer(tx, world.account, world.chat, pages.toList.map(page(_))))
    ()

  /** The messages the worker put in the outbox, in order. */
  private def outbox(world: ReportWorld): List[OutboundMessage] =
    world.uow.outbox.allEnqueued.map(row => OutboundMessage.fromJson(row.payload))

  test("nothing queued: no pass, nothing drawn"):
    val world = ReportWorld()
    val drawing = FakeDrawing()
    assert(!worker(world, drawing).runOnce())
    assert(drawing.drawn.isEmpty)

  test("a drawn request goes to the outbox as photo messages in page order, for the event's chat, and reads sent"):
    val world = ReportWorld()
    queue(world, 1, 2)
    val drawing = FakeDrawing()
    assert(worker(world, drawing).runOnce())
    assertEquals(drawing.drawn.asScala.toList, List("<svg id='1'/>" -> Some(world.chat), "<svg id='2'/>" -> Some(world.chat)))
    val rows = world.uow.outbox.allEnqueued
    assertEquals(rows.map(_.kind), List("interaction_reply", "interaction_reply"))
    assertEquals(rows.map(_.sendKey), List("image:1", "image:2"), "the key of each message makes a second publish a no-op")
    assertEquals(rows.map(_.accountId), List(Some(world.account), Some(world.account)))
    assertEquals(rows.map(_.nextAttemptAt), List(world.t0, world.t0))
    assertEquals(outbox(world).map(_.attachment.map(_.assetKey)), List(Some("asset:<svg id='1'/>"), Some("asset:<svg id='2'/>")))
    assertEquals(outbox(world).map(lines), List(List("caption 1"), List("caption 2")))
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.Sent))

  test("a page that cannot be drawn is sent as its text equivalent and counted by its template; the log names the kind"):
    val world = ReportWorld()
    queue(world, 1, 2)
    val log = ConcurrentLinkedQueue[String]()
    val fallbacks = Fallbacks()
    assert(worker(world, FakeDrawing(failing = Set("<svg id='2'/>")), line => log.add(line): Unit, fallbacks).runOnce())
    assertEquals(outbox(world).map(_.attachment.isDefined), List(true, false))
    assertEquals(lines(outbox(world)(1)), List(ImageCopy.inPlace("Water today: 2 of 2.0 l"), "caption 2"))
    assertEquals(fallbacks.templates.asScala.toList, List("water"))
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))
    assert(log.asScala.exists(line => line.contains("water") && line.contains("TimedOut")), log.toString)
    assert(!log.asScala.exists(_.contains("<svg")), "no SVG in the log")
    assert(!log.asScala.exists(_.contains("Water today")), "no text of the card in the log")

  test("a request claimed after its five seconds is sent as text without drawing (VA-FB-01)"):
    val world = ReportWorld()
    queue(world, 1)
    world.clock.at = world.t0.plus(CardDelivery.Deadline)
    val drawing = FakeDrawing()
    assert(worker(world, drawing).runOnce())
    assert(drawing.drawn.isEmpty, "a late request is not drawn")
    assertEquals(outbox(world).map(lines), List(List(ImageCopy.inPlace("Water today: 1 of 2.0 l"), "caption 1")))
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))

  test("a request a crashed worker left is claimed again once its claim lapsed, and sent once, as text"):
    val world = ReportWorld()
    queue(world, 1)
    // The first worker claims the request and dies before it publishes anything.
    assert(world.uow.reports.claim(world.t0, ReportWorker.Lease).isDefined)
    val drawing = FakeDrawing()
    assert(!worker(world, drawing).runOnce(), "the claim holds while its lease runs")
    world.clock.at = world.t0.plus(ReportWorker.Lease)
    assert(worker(world, drawing).runOnce(), "the restarted worker takes it")
    assert(drawing.drawn.isEmpty, "a request that was taken before is not drawn again: it may be what crashed the worker")
    assertEquals(outbox(world).map(_.attachment), List(None))
    assert(!worker(world, drawing).runOnce(), "and it is finished")
    assertEquals(world.uow.outbox.allEnqueued.size, 1)

  test("the purge deletes the requests made more than an hour ago, finished or not"):
    val world = ReportWorld()
    queue(world, 1)
    world.clock.at = world.t0.plus(Duration.ofMinutes(59))
    assertEquals(worker(world, FakeDrawing()).purge(), 0)
    world.clock.at = world.t0.plus(ReportWorker.Retention).plusSeconds(1)
    assertEquals(worker(world, FakeDrawing()).purge(), 1)
    assertEquals(world.uow.reports.all, Nil)

  test("a finished request keeps no pages and stays 24 hours, for the daily limit"):
    val world = ReportWorld()
    queue(world, 1)
    assert(worker(world, FakeDrawing()).runOnce())
    assertEquals(world.uow.reports.all.map(_.pagesKept), List(false), "the SVG goes when the request is finished")
    world.clock.at = world.t0.plus(CardDelivery.Window).minusSeconds(1)
    assertEquals(worker(world, FakeDrawing()).purge(), 0)
    world.clock.at = world.t0.plus(CardDelivery.Window).plusSeconds(1)
    assertEquals(worker(world, FakeDrawing()).purge(), 1)
    assertEquals(world.uow.reports.all, Nil)
