package dosegram.core.application.reports

import dosegram.contracts.AttachmentRef
import dosegram.contracts.Block
import dosegram.contracts.ChoiceAction
import dosegram.contracts.Inline
import dosegram.contracts.Node
import dosegram.contracts.ChatRef
import dosegram.contracts.MediaKind
import dosegram.contracts.OutboundMessage
import dosegram.core.domain.copy.ImageCopy
import dosegram.core.domain.copy.StickerCopy
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.CardMotion
import dosegram.core.ports.StickerImage
import dosegram.core.ports.StickerSetFailure
import dosegram.core.ports.StickerSetJob
import dosegram.core.ports.StickerSets
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

  /** Draws every page as `asset:<svg>` unless its SVG is in `failing`; records what it drew and for which chat. A page
    * whose SVG is in `alreadyDrawn` is ready without drawing.
    */
  private final class FakeDrawing(failing: Set[String] = Set.empty, alreadyDrawn: Set[String] = Set.empty)
      extends CardDrawing:
    val drawn = ConcurrentLinkedQueue[(String, Option[ChatRef])]()
    override def draw(page: CardPage, chat: Option[ChatRef]): Either[String, AttachmentRef] =
      drawn.add(page.svg -> chat)
      if failing(page.svg) then Left("TimedOut") else Right(reference(page))
    override def ready(page: CardPage): Option[AttachmentRef] = Option.when(alreadyDrawn(page.svg))(reference(page))
    private def reference(page: CardPage): AttachmentRef =
      AttachmentRef(MediaKind.Photo, s"asset:${page.svg}", "image/png", page.altText, Some(page.fileName))

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

  test("a late request still sends a page whose picture is already drawn as that image, and draws nothing"):
    val world = ReportWorld()
    queue(world, 1, 2)
    world.clock.at = world.t0.plus(CardDelivery.Deadline).plusSeconds(60)
    val drawing = FakeDrawing(alreadyDrawn = Set("<svg id='1'/>"))
    assert(worker(world, drawing).runOnce())
    assert(drawing.drawn.isEmpty, "a late request is not drawn")
    assertEquals(outbox(world).map(_.attachment.map(_.assetKey)), List(Some("asset:<svg id='1'/>"), None))
    assertEquals(lines(outbox(world)(1)), List(ImageCopy.inPlace("Water today: 2 of 2.0 l"), "caption 2"))
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))

  test("an animated answer has its own deadline: the still and the video's budget (VISUALS section 4.6)"):
    val world = ReportWorld()
    val motion = CardMotion(List("<svg id='frame'/>"), 20, "mood.motion.test", "dosegram-mood.mp4")
    val animated = page(1, "card.mood.v1")
    world.uow.transaction(tx =>
      CardDelivery(world.clock, world.ids)
        .answer(tx, world.account, world.chat, List(animated.copy(card = animated.card.copy(motion = Some(motion)))))
    )
    assertEquals(CardDelivery.MotionDeadline, CardDelivery.Deadline.plusSeconds(4))
    world.clock.at = world.t0.plus(CardDelivery.Deadline).plusSeconds(2)
    val drawing = FakeDrawing()
    assert(worker(world, drawing).runOnce())
    assertEquals(drawing.drawn.asScala.toList.map(_._1), List("<svg id='1'/>"), "claimed inside its own deadline, it is drawn")
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.Sent))

  test("an animated answer claimed after its own deadline is not drawn"):
    val world = ReportWorld()
    val motion = CardMotion(List("<svg id='frame'/>"), 20, "mood.motion.test", "dosegram-mood.mp4")
    val animated = page(1, "card.mood.v1")
    world.uow.transaction(tx =>
      CardDelivery(world.clock, world.ids)
        .answer(tx, world.account, world.chat, List(animated.copy(card = animated.card.copy(motion = Some(motion)))))
    )
    world.clock.at = world.t0.plus(CardDelivery.MotionDeadline)
    val drawing = FakeDrawing()
    assert(worker(world, drawing).runOnce())
    assert(drawing.drawn.isEmpty)
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))

  /** A vendor with sticker sets that records what it was asked to publish. */
  private final class FakeStickers(answer: Either[StickerSetFailure, String]) extends StickerSets:
    val published = ConcurrentLinkedQueue[(ChatRef, String, String, List[StickerImage])]()
    override def publish(
        chat: ChatRef,
        setKey: String,
        title: String,
        stickers: List[StickerImage]
    ): Either[StickerSetFailure, String] =
      published.add((chat, setKey, title, stickers))
      answer
    override def remove(vendor: String, setKey: String): Either[StickerSetFailure, Unit] = Right(())

  private def queueStickers(world: ReportWorld): Unit =
    val pages = List(1, 2).map(n => page(n, "card.mood.sticker.v1"))
    val busy = page(9).message
    world.uow.transaction(tx =>
      CardDelivery(world.clock, world.ids)
        .stickers(tx, world.account, world.chat, pages, StickerSetJob("mood_ab12", List("a", "b")), busy)
    )
    ()

  private def stickerWorker(world: ReportWorld, drawing: CardDrawing, stickers: StickerSets) =
    ReportWorker(world.uow, drawing, world.clock, world.ids, _ => (), _ => (), stickers)

  test("a sticker request draws its pages and publishes them as the set, in order; the answer carries its link"):
    val world = ReportWorld()
    queueStickers(world)
    val stickers = FakeStickers(Right("https://example.test/addstickers/mood_ab12"))
    assert(stickerWorker(world, FakeDrawing(), stickers).runOnce())
    val List((chat, setKey, title, images)) = stickers.published.asScala.toList: @unchecked
    assertEquals((chat, setKey, title), (world.chat, "mood_ab12", StickerCopy.title))
    assertEquals(images, List(StickerImage("asset:<svg id='1'/>", "a"), StickerImage("asset:<svg id='2'/>", "b")))
    val List(answer) = outbox(world): @unchecked
    assertEquals(answer.attachment, None, "the stickers are the set, not photos")
    assertEquals(lines(answer).head, StickerCopy.ready)
    val links = answer.body.flatMap { case Node.Paragraph(inlines) => inlines; case _ => Nil }.collect {
      case Inline.Link(_, url) => url
    }
    assertEquals(links, List("https://example.test/addstickers/mood_ab12"))
    val buttons = answer.blocks.collect { case Block.Choices(set) => set.choices.map(c => c.label -> c.action) }.flatten
    assertEquals(buttons, List(StickerCopy.Add -> ChoiceAction.OpenUrl("https://example.test/addstickers/mood_ab12")))
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.Sent))

  test("a set the vendor refuses is answered with the line that says so; a late request publishes nothing"):
    val refused = ReportWorld()
    queueStickers(refused)
    assert(stickerWorker(refused, FakeDrawing(), FakeStickers(Left(StickerSetFailure.Refused))).runOnce())
    assertEquals(outbox(refused).map(lines), List(List(StickerCopy.failed)))
    assertEquals(refused.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))

    val late = ReportWorld()
    queueStickers(late)
    late.clock.at = late.t0.plus(CardDelivery.Deadline)
    val drawing = FakeDrawing()
    val stickers = FakeStickers(Right("https://example.test/x"))
    assert(stickerWorker(late, drawing, stickers).runOnce())
    assert(drawing.drawn.isEmpty && stickers.published.isEmpty)
    assertEquals(outbox(late).map(lines), List(List(StickerCopy.failed)))

  test("a sticker page that cannot be drawn publishes no set"):
    val world = ReportWorld()
    queueStickers(world)
    val stickers = FakeStickers(Right("https://example.test/x"))
    assert(stickerWorker(world, FakeDrawing(failing = Set("<svg id='1'/>")), stickers).runOnce())
    assert(stickers.published.isEmpty)
    assertEquals(outbox(world).map(lines), List(List(StickerCopy.failed)))

  test("an account with an image underway is answered busy at once, and nothing more is queued"):
    val world = ReportWorld()
    queue(world, 1)
    val busy = page(9).message
    val answer = world.uow.transaction(tx =>
      CardDelivery(world.clock, world.ids)
        .stickers(tx, world.account, world.chat, List(page(1)), StickerSetJob("mood_ab12", List("a")), busy)
    )
    assertEquals(answer, List(busy))
    assertEquals(world.uow.reports.all.size, 1)

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

  // Leftover 2 of the verification of 2026-11-28: a request older than an hour when the app started was purged unsent.
  test("the purge never deletes an unfinished request, however old: the worker answers or drops it"):
    val world = ReportWorld()
    queue(world, 1)
    world.clock.at = world.t0.plus(Duration.ofDays(3))
    assertEquals(worker(world, FakeDrawing()).purge(), 0)
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.Queued))

  test("a request claimed long after it was made, on the same local day, is sent with a note naming when it was asked"):
    val world = ReportWorld()
    queue(world, 1, 2)
    // 12:00 in Kyiv when it was asked, 21:00 when the worker takes it.
    world.clock.at = world.t0.plus(Duration.ofHours(9))
    val drawing = FakeDrawing(alreadyDrawn = Set("<svg id='1'/>"))
    assert(worker(world, drawing).runOnce())
    assertEquals(outbox(world).map(_.attachment.map(_.assetKey)), List(Some("asset:<svg id='1'/>"), None))
    assertEquals(outbox(world).map(lines), List(
      List("caption 1", ImageCopy.lateAnswer("12:00")),
      List(ImageCopy.inPlace("Water today: 2 of 2.0 l"), "caption 2")
    ), "one note, on the answer's first message")
    assertEquals(ImageCopy.lateAnswer("12:00"), "Sent late: you asked for this at 12:00.")
    assertEquals(world.uow.reports.all.map(_.status), List(ReportStatus.TextFallback))

  test("a request claimed within two minutes of being made has no late note"):
    val world = ReportWorld()
    queue(world, 1)
    world.clock.at = world.t0.plus(ReportWorker.LateNoteAfter)
    assert(worker(world, FakeDrawing()).runOnce())
    assertEquals(outbox(world).map(lines), List(List(ImageCopy.inPlace("Water today: 1 of 2.0 l"), "caption 1")))

  test("a request of an earlier local day is not sent: it is finished as stale, counted and logged by id only"):
    val world = ReportWorld()
    queue(world, 1)
    // 12:00 in Kyiv when it was asked; 00:30 on the next day in Kyiv when the worker takes it.
    world.clock.at = world.t0.plus(Duration.ofMinutes(12 * 60 + 30))
    val log = ConcurrentLinkedQueue[String]()
    val dropped = ConcurrentLinkedQueue[String]()
    val drawing = FakeDrawing(alreadyDrawn = Set("<svg id='1'/>"))
    val stale = ReportWorker(world.uow, drawing, world.clock, world.ids, line => log.add(line): Unit, _ => (),
      dropped = template => dropped.add(template): Unit)
    assert(stale.runOnce())
    assertEquals(world.uow.outbox.allEnqueued, Nil, "yesterday's figures are not sent as today's answer")
    assertEquals(world.uow.reports.all.map(r => (r.status, r.pagesKept)), List((ReportStatus.TextFallback, false)))
    assertEquals(dropped.asScala.toList, List("water"))
    assertEquals(log.asScala.toList.map(_.replaceAll("[0-9a-f-]{36}", "<id>")), List("report <id>: dropped: Stale"))
    assert(!stale.runOnce(), "and it is finished")

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
