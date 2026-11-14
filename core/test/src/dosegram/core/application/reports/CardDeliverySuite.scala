package dosegram.core.application.reports

import dosegram.core.domain.copy.ImageCopy
import dosegram.core.ports.CardDrawing
import dosegram.core.ports.ReportStatus

import java.time.Duration

/** ROADMAP G4.3 on the event's side: the pages are queued on the event's transaction, or answered at once with their
  * text equivalents when the queue is full or the account is at its limits (VISUALS VA-FB-01, VA-FB-03, ARCHITECTURE
  * RND-07).
  */
class CardDeliverySuite extends munit.FunSuite:
  import ReportFixtures.*

  private def delivery(world: ReportWorld) = CardDelivery(world.clock, world.ids)

  test("an answer with images queues one request on the event's transaction and sends nothing at once"):
    val world = ReportWorld()
    val now = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(1), page(2))))
    assertEquals(now, Nil)
    val List(row) = world.uow.reports.all: @unchecked
    assertEquals(row.status, ReportStatus.Queued)
    assertEquals(row.request.pages, List(page(1), page(2)), "the pages keep their order")
    assertEquals(row.request.chat, world.chat)
    assertEquals(row.request.deadlineAt, world.t0.plus(CardDelivery.Deadline), "the text fallback of VA-FB-01")
    assertEquals(CardDelivery.Deadline, Duration.ofSeconds(5))

  test("a full queue answers with the text equivalents at once and queues nothing (RND-07, VA-FB-01)"):
    val world = ReportWorld()
    (1 to CardDelivery.QueueCapacity).foreach: n =>
      world.uow.transaction(tx => delivery(world).answer(tx, world.otherAccount(), world.chat, List(page(n))))
    val now = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(1), page(2))))
    assertEquals(now.map(lines), List(List(ImageCopy.inPlace("Water today: 1 of 2.0 l"), "caption 1"), List(ImageCopy.inPlace("Water today: 2 of 2.0 l"), "caption 2")))
    assert(now.forall(_.attachment.isEmpty), "no image is promised")
    assertEquals(now.map(_.dedupeKey), List("image:1", "image:2"), "each text keeps the key of its image")
    assertEquals(world.uow.reports.all.size, CardDelivery.QueueCapacity)

  test("a second image while one is underway for the account is sent as text (one render per account, VA-FB-03)"):
    val world = ReportWorld()
    world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(1))))
    val now = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(2))))
    assertEquals(now.map(lines), List(List(ImageCopy.inPlace("Water today: 2 of 2.0 l"), "caption 2")))
    assertEquals(world.uow.reports.all.size, 1)

  test("past 20 images in a day the image is sent as text with the local time the next one can be drawn"):
    val world = ReportWorld()
    (1 to CardDelivery.DailyLimit).foreach: n =>
      world.clock.at = world.t0.plusSeconds(60L * n)
      world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(n))))
      world.uow.reports.all.foreach(row => world.uow.reports.finish(row.request.id, ReportStatus.Sent, None, None, world.clock.at))
    world.clock.at = world.t0.plus(Duration.ofHours(3))
    val now = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(21))))
    // The first of the 20 was made at 10:01 UTC, 12:01 in Kyiv; it leaves the 24 hours at 12:01 the next day.
    assertEquals(
      now.map(lines),
      List(List(ImageCopy.inPlace("Water today: 21 of 2.0 l"), "caption 21", ImageCopy.dailyLimit(20, "12:01")))
    )
    assertEquals(world.uow.reports.all.size, CardDelivery.DailyLimit)

    world.clock.at = world.t0.plus(Duration.ofHours(24)).plusSeconds(61)
    val nextDay = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(22))))
    assertEquals(nextDay, Nil, "once the oldest is a day old an image is queued again")

  test("the daily limit still counts the images of 24 hours after the worker's hourly purge"):
    val world = ReportWorld()
    val worker = ReportWorker(world.uow, CardDrawing.unavailable, world.clock, world.ids, _ => (), _ => ())
    (1 to CardDelivery.DailyLimit).foreach: n =>
      world.clock.at = world.t0.plusSeconds(60L * n)
      world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(n))))
      assert(worker.runOnce(), s"request $n is sent")
    world.clock.at = world.t0.plus(Duration.ofHours(3))
    worker.purge()
    val now = world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, List(page(21))))
    assertEquals(now.map(_.attachment), List(None), "the 21st image of the day is sent as text")
    assert(lines(now.head).contains(ImageCopy.dailyLimit(20, "12:01")), lines(now.head).toString)

  test("no pages: nothing is queued and nothing is sent"):
    val world = ReportWorld()
    assertEquals(world.uow.transaction(tx => delivery(world).answer(tx, world.account, world.chat, Nil)), Nil)
    assertEquals(world.uow.reports.all, Nil)
