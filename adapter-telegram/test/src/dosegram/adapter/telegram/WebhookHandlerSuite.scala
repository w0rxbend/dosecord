package dosegram.adapter.telegram

import scala.collection.mutable.ListBuffer

/** ROADMAP G1.7, ARCHITECTURE section 7.2.1: the webhook endpoint as a pure function of the request. */
class WebhookHandlerSuite extends munit.FunSuite:

  private val config = WebhookConfig("path-secret-1", List("header-secret-new", "header-secret-old"), 1024L)

  private final class Rig(stored: Boolean = true):
    val updates = ListBuffer.empty[Update]
    var bodyReads = 0
    val handler = WebhookHandler(
      config,
      update =>
        updates += update
        stored
    )

    def request(
        body: String,
        path: String = "path-secret-1",
        header: Option[String] = Some("header-secret-new"),
        contentLength: Option[Long] = None
    ): WebhookRequest =
      WebhookRequest(
        path,
        header,
        contentLength,
        () =>
          bodyReads += 1
          body
      )

  private val message = ujson.write(BotApiFixtures.update("message-text"))

  test("a request with both secrets stores the update and is answered 200"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request(message)), WebhookResponse.Accepted)
    assertEquals(rig.updates.map(_.updateId).toList, List(900000001L))
    assertEquals(WebhookResponse.Accepted.status, 200)

  test("a wrong path secret is 404 and the body is never read"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request(message, path = "path-secret-2")), WebhookResponse.NotFound)
    assertEquals(rig.bodyReads, 0)
    assertEquals(rig.updates.toList, Nil)
    assertEquals(WebhookResponse.NotFound.status, 404)

  test("a wrong or missing header secret is 404 and the body is never read"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request(message, header = Some("guess"))), WebhookResponse.NotFound)
    assertEquals(rig.handler.handle(rig.request(message, header = None)), WebhookResponse.NotFound)
    assertEquals(rig.handler.handle(rig.request(message, header = Some(""))), WebhookResponse.NotFound)
    assertEquals(rig.bodyReads, 0)

  test("during a rotation the previous header secret is still accepted"):
    val rig = Rig()
    assertEquals(
      rig.handler.handle(rig.request(message, header = Some("header-secret-old"))),
      WebhookResponse.Accepted
    )

  test("a declared length over the limit is 413 without reading the body"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request(message, contentLength = Some(1025L))), WebhookResponse.TooLarge)
    assertEquals(rig.bodyReads, 0)
    assertEquals(WebhookResponse.TooLarge.status, 413)

  test("a body over the limit is 413 even when no length was declared"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request("x" * 1025)), WebhookResponse.TooLarge)
    assertEquals(rig.updates.toList, Nil)

  test("an update that could not be stored is 503, so Telegram sends it again"):
    val rig = Rig(stored = false)
    assertEquals(rig.handler.handle(rig.request(message)), WebhookResponse.Unavailable)
    assertEquals(WebhookResponse.Unavailable.status, 503)

  test("the same update delivered twice reaches the store twice: its key removes the duplicate"):
    val rig = Rig()
    rig.handler.handle(rig.request(message))
    rig.handler.handle(rig.request(message))
    assertEquals(rig.updates.map(_.updateId).toList, List(900000001L, 900000001L))

  test("a body that is not an update is acknowledged and dropped"):
    val rig = Rig()
    assertEquals(rig.handler.handle(rig.request("not json")), WebhookResponse.Accepted)
    assertEquals(rig.handler.handle(rig.request("""{"no_update_id":1}""")), WebhookResponse.Accepted)
    assertEquals(rig.updates.toList, Nil)

  test("an update of an unknown kind is stored as unknown"):
    val rig = Rig()
    rig.handler.handle(rig.request(ujson.write(BotApiFixtures.update("unknown-kind"))))
    assertEquals(rig.updates.toList, List(Update(900000017L, UpdateBody.Unknown("kind_from_a_later_api_version"))))
