package dosegram.app

import dosegram.core.ports.Clock
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.MiniAppSettings
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.db.Fixtures
import dosegram.infra.db.PgSuite
import dosegram.infra.db.sql
import ox.supervised

import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.jdk.OptionConverters.*
import scala.language.implicitConversions

/** ROADMAP G1.12 and G3.3: the Mini App surface behind `MINIAPP_ENABLED`, one origin for `/app/` and `/api/v1/`, and
  * the header set of research brief 06 section 12.4 on every answer of that origin (ARCHITECTURE API-20).
  */
final class MiniAppEdgeSuite extends PgSuite:
  import MiniAppEdgeSuite.*

  private val client = HttpClient.newHttpClient()
  private lazy val fixtures = Fixtures(dataSource)

  /** A built Mini App as `vite build` leaves it, and a file next to it that must never be served. */
  private lazy val dist: Path =
    val root = Files.createTempDirectory("dosegram-edge")
    val dist = Files.createDirectories(root.resolve("dist"))
    Files.createDirectories(dist.resolve("assets"))
    Files.writeString(dist.resolve("index.html"), IndexHtml)
    Files.writeString(dist.resolve("assets/index-a1b2c3d4.js"), "export const app = 1;")
    Files.writeString(dist.resolve("assets/index-a1b2c3d4.js.map"), """{"version":3}""")
    Files.writeString(dist.resolve("assets/index-e5f6a7b8.css"), "body{margin:0}")
    Files.writeString(root.resolve("secret.txt"), "outside-the-app")
    dist

  private def databaseUrl: String =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}"

  private def settings(miniApp: Option[MiniAppSettings]): Settings =
    Settings(
      enabledAdapters = List(Adapter.Console),
      databaseUrl = databaseUrl,
      callbackKeys = Settings.parseCallbackKeys("0:MDEyMzQ1Njc4OWFiY2RlZg").fold(error => fail(error), identity),
      role = Role.Gateway,
      instanceId = "edge",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = Some(LaunchRig.ConsoleUser),
      telegramToken = Some(BotToken),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Test,
      httpPort = freePort(),
      miniApp = miniApp
    )

  private def enabled(staticDir: Option[Path] = Some(dist)): Settings =
    settings(Some(MiniAppSettings(staticDir.map(_.toString), botId = None, initDataPublicKey = None, hmacFallback = true)))

  /** Starts the application of [[Launch.start]] with these settings and runs `test` against it. */
  private def launched[A](settings: Settings)(test: => A): A =
    val rig = LaunchRig(Clock.system)
    try
      supervised:
        val parts = Launch.Parts(Clock.system, Map("console" -> rig.console), Workers.Cadence.Production)
        val drain = Launch.start(settings, dataSource, parts, log = _ => ())
        try test
        finally
          drain.execute()
          ()
    finally rig.close()

  private val stopShared = CountDownLatch(1)

  /** One launch with the Mini App on, shared by the tests that only read from it; it ends with the suite. */
  private lazy val shared: Settings =
    val on = enabled()
    val started = CountDownLatch(1)
    val launch = Thread: () =>
      launched(on):
        started.countDown()
        stopShared.await()
    launch.setDaemon(true)
    launch.start()
    assert(started.await(60, TimeUnit.SECONDS), "the shared launch did not start")
    on

  override def afterAll(): Unit =
    stopShared.countDown()
    super.afterAll()

  private def call(
      port: Int,
      path: String,
      method: String = "GET",
      body: Option[String] = None,
      token: String = ""
  ): HttpResponse[String] =
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
    val request = HttpRequest.newBuilder(URI(s"http://127.0.0.1:$port$path")).method(method, publisher)
    if body.isDefined then request.header("Content-Type", "application/json")
    if token.nonEmpty then request.header("Authorization", s"Bearer $token")
    client.send(request.build(), HttpResponse.BodyHandlers.ofString())

  private def header(response: HttpResponse[String], name: String): Option[String] =
    response.headers().firstValue(name).toScala

  private def telegramAccount(): Long =
    val accountId = fixtures.account("UTC")
    val telegramUserId = 6000000L + scala.util.Random.nextInt(1000000)
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, linked_at)
            VALUES (${UUID.randomUUID()}, $accountId, 'telegram', ${telegramUserId.toString}, ${Instant.now()})"""
        .execute()
    finally conn.close()
    telegramUserId

  // ---------- the switch ----------

  test("with the Mini App off nothing listens on the public port, and the health port has no /app/ or /api/"):
    val off = settings(miniApp = None)
    launched(off):
      assertEquals(call(off.healthPort, "/healthz").statusCode(), 200)
      assertEquals(call(off.healthPort, "/app/").statusCode(), 404)
      assertEquals(call(off.healthPort, "/api/v1/today").statusCode(), 404)
      assertEquals(call(off.healthPort, "/api/v1/session", "POST", Some("{}")).statusCode(), 404)
      intercept[ConnectException](call(off.httpPort, "/app/"))

  test("with the Mini App on the health port still has no /app/ or /api/"):
    val on = shared
    locally:
      assertEquals(call(on.healthPort, "/healthz").statusCode(), 200)
      assertEquals(call(on.healthPort, "/api/v1/today").statusCode(), 404)
      assertEquals(call(on.httpPort, "/healthz").statusCode(), 404)

  // ---------- headers (API-20) ----------

  test("every answer of the public origin carries the full header set and no CORS or framing header"):
    val on = shared
    locally:
      val answers = List(
        call(on.httpPort, "/app/"),
        call(on.httpPort, "/app/meds/new"),
        call(on.httpPort, "/app/assets/index-a1b2c3d4.js"),
        call(on.httpPort, "/app/assets/missing.js"),
        call(on.httpPort, "/api/v1/today"),
        call(on.httpPort, "/api/v1/session", "POST", Some("""{"initData":"x"}""")),
        call(on.httpPort, "/api/v1/session", "POST", Some("{broken")),
        call(on.httpPort, "/api/v1/unknown"),
        call(on.httpPort, "/"),
        call(on.httpPort, "/favicon.ico")
      )
      answers.foreach: answer =>
        val where = s"${answer.request().method()} ${answer.uri().getPath} -> ${answer.statusCode()}"
        ExpectedHeaders.foreach((name, value) => assertEquals(header(answer, name), Some(value), s"$name on $where"))
        ForbiddenHeaders.foreach(name => assertEquals(header(answer, name), None, s"$name on $where"))
      assertEquals(answers.map(_.statusCode()), List(200, 200, 200, 404, 401, 401, 400, 404, 404, 404))

  test("every /api/ answer is no-store, also an error"):
    val on = shared
    locally:
      List(call(on.httpPort, "/api/v1/today"), call(on.httpPort, "/api/v1/unknown")).foreach: answer =>
        assertEquals(header(answer, "Cache-Control"), Some("no-store"))
        assertEquals(header(answer, "Content-Type"), Some("application/problem+json"))

  // ---------- static files (MA-HOST-3, MA-HOST-4) ----------

  test("/app/ serves the shell with no-store; a hashed asset is immutable"):
    val on = shared
    locally:
      val shell = call(on.httpPort, "/app/")
      assertEquals(shell.body(), IndexHtml)
      assertEquals(header(shell, "Content-Type"), Some("text/html; charset=utf-8"))
      assertEquals(header(shell, "Cache-Control"), Some("no-store"))
      assertEquals(call(on.httpPort, "/app").body(), IndexHtml)
      val script = call(on.httpPort, "/app/assets/index-a1b2c3d4.js")
      assertEquals(script.body(), "export const app = 1;")
      assertEquals(header(script, "Content-Type"), Some("text/javascript; charset=utf-8"))
      assertEquals(header(script, "Cache-Control"), Some("public, max-age=31536000, immutable"))
      val style = call(on.httpPort, "/app/assets/index-e5f6a7b8.css")
      assertEquals(header(style, "Content-Type"), Some("text/css; charset=utf-8"))

  test("a path without a file extension answers the shell; a missing file answers 404"):
    val on = shared
    locally:
      List("/app/meds", "/app/meds/3f2a/edit", "/app/day/2026-11-13").foreach: route =>
        val answer = call(on.httpPort, route)
        assertEquals(answer.statusCode(), 200, route)
        assertEquals(answer.body(), IndexHtml, route)
        assertEquals(header(answer, "Cache-Control"), Some("no-store"), route)
      assertEquals(call(on.httpPort, "/app/assets/missing.js").statusCode(), 404)
      assertEquals(call(on.httpPort, "/app/missing.css").statusCode(), 404)

  test("source maps are not served, and no path leaves the application directory"):
    val on = shared
    locally:
      assertEquals(call(on.httpPort, "/app/assets/index-a1b2c3d4.js.map").statusCode(), 404)
      List("/app/../secret.txt", "/app/%2e%2e/secret.txt", "/app/assets/..%2f..%2fsecret.txt", "/app/assets/%2e%2e/%2e%2e/secret.txt")
        .foreach: path =>
          val answer = call(on.httpPort, path)
          assert(!answer.body().contains("outside-the-app"), s"$path served a file outside the application")
          assertEquals(answer.statusCode(), 404, path)

  test("without a static directory /app/ answers 404 and the API is served"):
    val on = enabled(staticDir = None)
    launched(on):
      assertEquals(call(on.httpPort, "/app/").statusCode(), 404)
      assertEquals(call(on.httpPort, "/api/v1/today").statusCode(), 401)

  // ---------- the API through the application's own wiring ----------

  test("a session opens with launch data signed for the configured bot token, and Today answers"):
    val on = shared
    val telegramUserId = telegramAccount()
    locally:
      val initData = signedInitData(telegramUserId, Instant.now())
      val session = call(on.httpPort, "/api/v1/session", "POST", Some(ujson.write(ujson.Obj("initData" -> initData))))
      assertEquals(session.statusCode(), 201, session.body())
      assertEquals(header(session, "Set-Cookie"), None)
      val today = call(on.httpPort, "/api/v1/today", token = ujson.read(session.body())("token").str)
      assertEquals(today.statusCode(), 200, today.body())
      assertEquals(header(today, "Cache-Control"), Some("no-store"))

  test("launch data signed with another token is refused"):
    val on = shared
    val telegramUserId = telegramAccount()
    locally:
      val forged = signedInitData(telegramUserId, Instant.now(), token = "7000000002:AAAnotherFakeTokenForTests")
      val answer = call(on.httpPort, "/api/v1/session", "POST", Some(ujson.write(ujson.Obj("initData" -> forged))))
      assertEquals(answer.statusCode(), 401, answer.body())

object MiniAppEdgeSuite:
  private val BotToken = "7000000001:AAFakeTokenForTestsOnly_0123456789abcde"
  private val IndexHtml = "<!doctype html><title>Dosegram</title><div id=\"app\"></div>"

  // Research brief 06 section 12.4, written out.
  private val ExpectedHeaders = List(
    "Content-Security-Policy" ->
      ("default-src 'none'; script-src 'self' https://telegram.org; style-src 'self'; img-src 'self' blob: data:; " +
        "font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; object-src 'none'; " +
        "frame-ancestors https://web.telegram.org; require-trusted-types-for 'script'"),
    "Strict-Transport-Security" -> "max-age=31536000",
    "X-Content-Type-Options" -> "nosniff",
    "Referrer-Policy" -> "no-referrer",
    "Permissions-Policy" -> "camera=(), microphone=(), geolocation=(), payment=(), usb=()",
    "Cross-Origin-Resource-Policy" -> "same-origin"
  )

  // No CORS header on any route (API-20); X-Frame-Options would break Telegram Web (brief 06, note 1).
  private val ForbiddenHeaders = List(
    "Access-Control-Allow-Origin",
    "Access-Control-Allow-Credentials",
    "Access-Control-Allow-Headers",
    "X-Frame-Options",
    "Set-Cookie",
    "Server"
  )

  private def hmac(key: Array[Byte], message: String): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message.getBytes(UTF_8))

  /** The published HMAC scheme of the Mini App platform, written out for this suite. */
  private def signedInitData(telegramUserId: Long, at: Instant, token: String = BotToken): String =
    val fields = Map("auth_date" -> at.getEpochSecond.toString, "user" -> s"""{"id":$telegramUserId}""")
    val checkString = fields.toList.sortBy(_._1).map((key, value) => s"$key=$value").mkString("\n")
    val hash = HexFormat.of().formatHex(hmac(hmac("WebAppData".getBytes(UTF_8), token), checkString))
    (fields + ("hash" -> hash)).toList.map((key, value) => s"$key=${URLEncoder.encode(value, UTF_8)}").mkString("&")

