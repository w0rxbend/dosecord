package dosegram.adapter.telegram

import dosegram.adapter.telegram.LongPollReader.Outcome

import java.time.Duration
import scala.collection.mutable.ListBuffer

/** ROADMAP G1.6, ARCHITECTURE section 7.2.2: the long-poll reader on the fake wire. */
class LongPollReaderSuite extends munit.FunSuite:

  private final class Rig(resumeAfter: Option[Long] = None):
    val timer = ManualTimer(TelegramRig.T0)
    val wire = FakeTelegramTransport(timer, idlePoll = Duration.ZERO)
    val delivered = ListBuffer.empty[Long]
    val logs = ListBuffer.empty[String]
    val slept = ListBuffer.empty[Duration]
    var accept: Update => Boolean = _ => true
    val reader: LongPollReader = LongPollReader(
      BotApi(wire),
      update =>
        val accepted = accept(update)
        if accepted then delivered += update.updateId
        accepted
      ,
      resumeAfter,
      logs += _,
      wait => { slept += wait; () }
    )

    def offsets: List[Option[Long]] = wire.calls("getUpdates").map(_.number("offset"))

  private def batch(names: String*): ujson.Value =
    ujson.Obj("ok" -> true, "result" -> ujson.Arr.from(names.map(BotApiFixtures.update)))

  test("updates are delivered in update_id order and the next poll confirms them by its offset"):
    val rig = Rig()
    rig.wire.replyNext("getUpdates", 200, batch("command-start", "message-text", "message-reply"))
    assertEquals(rig.reader.pollOnce(), Outcome.Delivered(3))
    assertEquals(rig.delivered.toList, List(900000001L, 900000002L, 900000003L))
    rig.reader.pollOnce()
    assertEquals(rig.offsets, List(None, Some(900000004L)))

  test("an update the wire delivers again is ignored"):
    val rig = Rig()
    rig.wire.replyNext("getUpdates", 200, batch("message-text", "message-reply"))
    rig.wire.replyNext("getUpdates", 200, batch("message-text", "message-reply", "command-start"))
    rig.reader.pollOnce()
    assertEquals(rig.reader.pollOnce(), Outcome.Delivered(1))
    assertEquals(rig.delivered.toList, List(900000001L, 900000002L, 900000003L))

  test("a resume cursor continues after it: nothing at or below it is delivered"):
    val rig = Rig(resumeAfter = Some(900000002L))
    rig.wire.enqueueUpdates(
      BotApiFixtures.update("message-text"),
      BotApiFixtures.update("message-reply"),
      BotApiFixtures.update("command-start")
    )
    rig.reader.pollOnce()
    assertEquals(rig.offsets, List(Some(900000003L)))
    assertEquals(rig.delivered.toList, List(900000003L))

  test("an update the mediator cannot take keeps the offset, so it comes again"):
    val rig = Rig()
    rig.wire.enqueueUpdates(BotApiFixtures.update("message-text"), BotApiFixtures.update("message-reply"))
    rig.accept = _.updateId != 900000002L
    assertEquals(rig.reader.pollOnce(), Outcome.Retry(LongPollReader.RetryDelay))
    rig.accept = _ => true
    assertEquals(rig.reader.pollOnce(), Outcome.Delivered(1))
    assertEquals(rig.offsets, List(None, Some(900000002L)))
    assertEquals(rig.delivered.toList, List(900000001L, 900000002L))

  test("a second consumer on the same token stops the reader with a clear error"):
    val rig = Rig()
    val conflict = BotApiFixtures.error("conflict-409")
    rig.wire.replyNext("getUpdates", conflict.status, conflict.body)
    val error = intercept[TelegramFatal](rig.reader.run())
    assert(error.getMessage.contains("another consumer"), error.getMessage)
    assertEquals(rig.wire.calls("getUpdates").size, 1)

  test("a rejected token stops the reader"):
    val rig = Rig()
    val unauthorized = BotApiFixtures.error("unauthorized-401")
    rig.wire.replyNext("getUpdates", unauthorized.status, unauthorized.body)
    assert(intercept[TelegramFatal](rig.reader.run()).getMessage.contains("token was rejected"))

  test("a 429 waits the time the API asked for"):
    val rig = Rig()
    val flood = BotApiFixtures.error("flood-429")
    rig.wire.replyNext("getUpdates", flood.status, flood.body)
    assertEquals(rig.reader.pollOnce(), Outcome.Retry(Duration.ofSeconds(30)))

  test("an outage is retried after a pause and logged by its kind only"):
    val rig = Rig()
    rig.wire.replyNext("getUpdates", WireReply.Http(502, "<html>CANARY-PROXY-BODY</html>"))
    assertEquals(rig.reader.pollOnce(), Outcome.Retry(LongPollReader.RetryDelay))
    assertEquals(rig.logs.toList, List("telegram getUpdates failed: Unavailable"))

  test("run polls until it is stopped and sleeps between failed polls"):
    val rig = Rig()
    rig.wire.replyNext("getUpdates", WireReply.Failed(WireFailure.Timeout))
    rig.wire.enqueueUpdates(BotApiFixtures.update("message-text"))
    rig.accept = _ =>
      rig.reader.stop()
      true
    rig.reader.run()
    assertEquals(rig.slept.toList, List(LongPollReader.RetryDelay))
    assertEquals(rig.delivered.toList, List(900000001L))
