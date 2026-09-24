package dosecord.adapter.discord

import net.dv8tion.jda.api.audio.AudioModuleConfig
import net.dv8tion.jda.api.requests.RestConfig
import net.dv8tion.jda.internal.JDAImpl
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig
import net.dv8tion.jda.internal.utils.config.MetaConfig
import net.dv8tion.jda.internal.utils.config.SessionConfig
import net.dv8tion.jda.internal.utils.config.ThreadingConfig

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** ROADMAP M2.2 acceptance: the fixture asserts `enforce_nonce: true` in the JSON body — the M0.8 spike's mock-REST
  * approach reproduced inside this module. A real JDA 6.6.0 instance (never gateway-logged-in) has its REST base
  * pointed at a local mock; a real `sendMessage(...)` through the exact action sequence of [[JdaTransport.sendMessage]]
  * is captured verbatim. The nonce is `base64url(sha256(sendKey)).take(22)` (DESIGN.md section 5); `silent` maps to
  * `SUPPRESS_NOTIFICATIONS` (flags bit 12) and discreet preview suppression to `SUPPRESS_EMBEDS` (bit 2).
  */
class DiscordNonceFixtureSuite extends munit.FunSuite:

  /** The mock Discord REST: one POST per call, bodies captured verbatim, minimal valid entities answered. */
  private final class MockRest:
    val bodies = ListBuffer.empty[(String, String)]
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", (exchange: HttpExchange) =>
      val path = exchange.getRequestURI.getPath
      val body = String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)
      bodies += ((path, body))
      val response =
        if path.endsWith("/channels") then
          """{"id":"456","type":1,"recipients":[{"id":"123","username":"owner","global_name":"Owner","discriminator":"0"}]}"""
        else
          """{"id":"789","channel_id":"456","author":{"id":"999","username":"dosecord","global_name":"Dosecord","discriminator":"0"},"content":"ok","timestamp":"2026-09-21T00:00:00.000000+00:00","edited_timestamp":null,"tts":false,"mention_everyone":false,"mentions":[],"mention_roles":[],"attachments":[],"embeds":[],"pinned":false,"type":0}"""
      val bytes = response.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    )
    server.start()
    def baseUrl: String = s"http://127.0.0.1:${server.getAddress.getPort}"
    def stop(): Unit = server.stop(0)

  private def withMockJda[A](f: (JDAImpl, MockRest) => A): A =
    val mock = MockRest()
    val threading = ThreadingConfig.getDefault()
    threading.init(() => "fixture")
    val jda = JDAImpl(
      AuthorizationConfig("fixture-token"),
      SessionConfig.getDefault(),
      threading,
      MetaConfig.getDefault(),
      RestConfig().setBaseUrl(mock.baseUrl),
      AudioModuleConfig()
    )
    // The REST requester is normally built by login(); the fixture never touches the gateway.
    jda.initRequester()
    // Parsing a created message asks for the self user (the author id match); login would set it from GET /users/@me.
    val selfUser = net.dv8tion.jda.internal.entities.SelfUserImpl(999L, jda).setName("dosecord")
    val selfUserField = classOf[JDAImpl].getDeclaredField("selfUser")
    selfUserField.setAccessible(true)
    selfUserField.set(jda, selfUser)
    try f(jda, mock)
    finally
      try jda.shutdown()
      catch case _: Exception => ()
      mock.stop()

  /** Drives the exact action sequence of `JdaTransport.sendMessage` against the mock REST. */
  private def realSend(jda: JDAImpl, message: DiscordMessage, nonce: String): Unit =
    val channel = jda.openPrivateChannelById("123").complete()
    val action = channel.sendMessage(message.text)
    if message.silent then action.setSuppressedNotifications(true)
    if message.suppressPreview then action.setSuppressEmbeds(true)
    action.setComponents(JdaTransport.componentsOf(message).asJava)
    action.setNonce(nonce)
    action.complete()
    ()

  test("a nonce-bearing send posts enforce_nonce: true with the base64url(sha256(sendKey)).take(22) nonce"):
    val sendKey = "outbox:discord:interaction_reply:42"
    val expectedNonce = DiscordAdapter.nonceOf(sendKey)
    assertEquals(expectedNonce.length, 22, "the nonce is 22 chars (Discord's 25-char bound)")
    withMockJda: (jda, mock) =>
      realSend(jda, DiscordMessage(text = "M2.2 nonce fixture"), expectedNonce)
      val posts = mock.bodies.toList.filter(_._1 == "/channels/456/messages")
      assertEquals(posts.size, 1, s"exactly one message POST: ${mock.bodies.toList}")
      val body = posts.head._2
      assert(body.contains("\"enforce_nonce\":true"), s"enforce_nonce missing: $body")
      assert(body.contains(s"\"nonce\":\"$expectedNonce\""), s"nonce missing: $body")

  test("silent and discreet flags and component rows serialize into the same body"):
    withMockJda: (jda, mock) =>
      val discordMessage = DiscordMessage(
        text = "M2.2 flags fixture",
        components =
          List(DiscordComponent.ButtonRow(List(DiscordButton("Taken", "dc:token", DiscordButtonStyle.Success)))),
        silent = true,
        suppressPreview = true
      )
      realSend(jda, discordMessage, DiscordAdapter.nonceOf("outbox:discord:reminder:7"))
      val body = mock.bodies.toList.filter(_._1 == "/channels/456/messages").head._2
      // SUPPRESS_NOTIFICATIONS (1 << 12) + SUPPRESS_EMBEDS (1 << 2) = 4096 + 4 = 4100.
      assert(body.contains("\"flags\":4100"), s"SUPPRESS_NOTIFICATIONS + SUPPRESS_EMBEDS missing: $body")
      assert(
        body.contains("\"components\":[") && body.contains("\"custom_id\":\"dc:token\""),
        s"component rows missing: $body"
      )
