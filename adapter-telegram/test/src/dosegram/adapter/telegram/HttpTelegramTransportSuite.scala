package dosegram.adapter.telegram

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

/** The HTTP transport against a loopback server of the JDK: the request it puts on the wire, and what it reports when
  * the wire fails. No request leaves the machine.
  */
class HttpTelegramTransportSuite extends munit.FunSuite:

  private val token = BotToken("555000111:CANARY-token_value")

  private final case class Seen(path: String, contentType: String, body: Array[Byte])

  /** A server that records the one request it gets and answers it with `status` and `reply` after `delay`. */
  private def withServer[A](status: Int, reply: String, delay: Duration = Duration.ZERO)(
      test: (HttpTelegramTransport, AtomicReference[Seen]) => A
  ): A =
    val seen = AtomicReference[Seen]()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) =>
        seen.set(
          Seen(
            exchange.getRequestURI.getPath,
            exchange.getRequestHeaders.getFirst("Content-Type"),
            exchange.getRequestBody.readAllBytes()
          )
        )
        Thread.sleep(delay.toMillis)
        val bytes = reply.getBytes(UTF_8)
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
    )
    server.start()
    val transport = HttpTelegramTransport(token, s"http://127.0.0.1:${server.getAddress.getPort}", () => "test-boundary")
    try test(transport, seen)
    finally
      transport.close()
      server.stop(0)

  private def request(file: Option[WireFile] = None, timeout: Duration = Duration.ofSeconds(5)): WireRequest =
    WireRequest(
      "sendMessage",
      ujson.Obj("chat_id" -> 7001, "text" -> "héllo", "reply_markup" -> ujson.Obj("inline_keyboard" -> ujson.Arr())),
      file,
      timeout
    )

  test("a call is a JSON POST to /bot<token>/<method> and its reply comes back verbatim"):
    withServer(200, """{"ok":true,"result":true}"""): (transport, seen) =>
      assertEquals(transport.call(request()), WireReply.Http(200, """{"ok":true,"result":true}"""))
      assertEquals(seen.get().path, "/bot555000111:CANARY-token_value/sendMessage")
      assertEquals(seen.get().contentType, "application/json")
      assertEquals(ujson.read(String(seen.get().body, UTF_8)), request().params: ujson.Value)

  test("an error status is a reply, not a failure"):
    withServer(429, """{"ok":false,"error_code":429}"""): (transport, _) =>
      assertEquals(transport.call(request()), WireReply.Http(429, """{"ok":false,"error_code":429}"""))

  test("an upload is multipart: every parameter as a text part, then the file bytes"):
    withServer(200, """{"ok":true,"result":true}"""): (transport, seen) =>
      val file = WireFile("photo", "card \"1\".png", "image/png", Array[Byte](1, 2, 3, -1))
      transport.call(request(Some(file)))
      assertEquals(seen.get().contentType, "multipart/form-data; boundary=test-boundary")
      val body = String(seen.get().body, ISO_8859_1)
      val expected =
        "--test-boundary\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n7001\r\n" +
          "--test-boundary\r\nContent-Disposition: form-data; name=\"text\"\r\n\r\n" +
          String("héllo".getBytes(UTF_8), ISO_8859_1) + "\r\n" +
          "--test-boundary\r\nContent-Disposition: form-data; name=\"reply_markup\"\r\n\r\n" +
          "{\"inline_keyboard\":[]}\r\n" +
          "--test-boundary\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"card__1_.png\"\r\n" +
          "Content-Type: image/png\r\n\r\n" + String(Array[Byte](1, 2, 3, -1), ISO_8859_1) +
          "\r\n--test-boundary--\r\n"
      assertEquals(body, expected)

  test("a reply slower than the call's timeout is a Timeout failure"):
    withServer(200, "{}", delay = Duration.ofMillis(600)): (transport, _) =>
      assertEquals(
        transport.call(request(timeout = Duration.ofMillis(100))),
        WireReply.Failed(WireFailure.Timeout)
      )

  test("a server that is gone is a failure value: nothing is thrown that could quote the URL"):
    val closed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val port = closed.getAddress.getPort
    closed.stop(0)
    val transport = HttpTelegramTransport(token, s"http://127.0.0.1:$port")
    // Whether the closed port refuses or stays silent depends on the host: either failure kind is right.
    try
      transport.call(request(timeout = Duration.ofMillis(500))) match
        case WireReply.Failed(_) => ()
        case other               => fail(s"expected a failure, got $other")
    finally transport.close()

  test("the token does not show in its text form, and a malformed token is refused without quoting it"):
    assertEquals(token.toString, "BotToken(***)")
    val error = intercept[IllegalArgumentException](BotToken("CANARY not a token"))
    assert(!error.getMessage.contains("CANARY"), error.getMessage)
