package dosegram.api

import dosegram.infra.crypto.DataKeys
import dosegram.api.auth.InMemorySessionStore
import dosegram.api.auth.InitDataHmac
import dosegram.api.auth.InitDataSuite
import dosegram.api.auth.TelegramInitDataVerifier
import dosegram.core.application.Application
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.CallbackKey
import dosegram.core.chat.CallbackKeys
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.infra.db.Fixtures
import dosegram.infra.db.MutableClock
import dosegram.infra.db.PgSuite
import dosegram.infra.db.TransactionalTracking
import dosegram.infra.db.PgUnitOfWork
import dosegram.infra.db.sql
import ox.supervised
import sttp.tapir.server.netty.sync.NettySyncServer
import sttp.tapir.server.netty.sync.NettySyncServerOptions

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.random.RandomGenerator
import scala.collection.mutable.ListBuffer
import scala.jdk.OptionConverters.*
import scala.language.implicitConversions

/** One HTTP answer of the API under test. */
final case class Reply(status: Int, headers: Map[String, List[String]], body: String):
  def json: ujson.Value = ujson.read(body)
  def header(name: String): Option[String] = headers.get(name.toLowerCase).flatMap(_.headOption)
  def code: String = json("code").str
  def fieldErrors: List[(String, String)] = json("errors").arr.toList.map(e => e("field").str -> e("code").str)

  /** The body without the random request id of a problem document, for a check that a value is absent: a request id
    * can contain any digits.
    */
  def bodyWithoutRequestId: String = body.replaceAll("\"requestId\":\"[^\"]*\"", "")

/** The route suites: the real Netty interpreter with the module's endpoints and decode-failure handler, on
  * Testcontainers Postgres, behind a clock the test steps. Launch data is signed with the HMAC scheme and a token that
  * exists only here.
  */
trait ApiSuite extends PgSuite:
  import ApiSuite.*

  protected val clock: MutableClock = MutableClock(T0)
  protected val sessionStore: InMemorySessionStore = InMemorySessionStore()
  protected val logged: ListBuffer[String] = ListBuffer.empty
  protected lazy val fixtures: Fixtures = Fixtures(dataSource)
  protected lazy val uow: PgUnitOfWork = PgUnitOfWork(dataSource, clock, DataKeys.development)

  // The tracking use cases over the same database: the module reads through them, a test seeds through them.
  protected lazy val tracking: TransactionalTracking =
    TransactionalTracking(uow, clock, IdSource.uuidV7(RandomGenerator.getDefault))

  private val stopped = CountDownLatch(1)
  private val bound = CompletableFuture[Int]()
  private val client = HttpClient.newHttpClient()
  private val launches = AtomicLong(0)
  private val telegramUsers = AtomicLong(5000000)

  private lazy val module = ApiModule(
    uow,
    clock,
    TelegramInitDataVerifier(ed25519 = None, hmacSecret = Some(InitDataHmac.secretKeyFor(BotToken))),
    sessionStore,
    InMemoryIdempotencyStore(),
    TrackingUseCases(
      tracking.statsService,
      tracking.hydrationService,
      tracking.moodService,
      tracking.sleepService,
      tracking.nutritionService
    ),
    ApiSuite.BlobStates,
    line => logged.synchronized { logged += line; () },
    Application.exportRequests(clock, ApiSuite.Codec)
  )

  override def beforeAll(): Unit =
    super.beforeAll()
    val endpoints = module.endpoints
    val options = NettySyncServerOptions.customiseInterceptors
      .decodeFailureHandler(ApiModule.decodeFailureHandler)
      .serverLog(None)
      .options
    val server = Thread: () =>
      supervised:
        val binding = NettySyncServer(options).host("127.0.0.1").port(0).addEndpoints(endpoints).start()
        bound.complete(binding.port)
        stopped.await()
        binding.stop()
    server.setDaemon(true)
    server.start()
    bound.get(30, TimeUnit.SECONDS)
    ()

  override def afterAll(): Unit =
    stopped.countDown()
    super.afterAll()

  protected def sweep(): Int = module.sweep()

  /** Moves the clock to 05:00 UTC of the next day: a suite shares one clock, and a test about a day's doses needs
    * the morning slots still ahead.
    */
  protected def startNewDay(): Unit =
    clock.at = clock.now().atZone(ZoneOffset.UTC).toLocalDate.plusDays(1).atTime(5, 0).toInstant(ZoneOffset.UTC)

  // ---------- accounts and sessions ----------

  /** An account that the chat created for a Telegram user, as `/start` leaves it when the user chose Medication. */
  protected def telegramAccount(timezone: String = "UTC"): TelegramAccount =
    val account = accountWithoutConsent(timezone)
    fixtures.grant(account.accountId, ConsentPurpose.Medication)
    account

  /** An account with no consent at all: the suites about consent grant their own. */
  protected def accountWithoutConsent(timezone: String = "UTC"): TelegramAccount =
    val accountId = fixtures.account(timezone)
    val telegramUserId = telegramUsers.incrementAndGet()
    val conn = dataSource.getConnection
    try
      given Connection = conn
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id, linked_at)
            VALUES (${UUID.randomUUID()}, $accountId, 'telegram', ${telegramUserId.toString}, ${clock.now()})"""
        .execute()
    finally conn.close()
    TelegramAccount(accountId, telegramUserId)

  /** Launch data for the user, signed at the current test time; every call yields a different value. */
  protected def initDataFor(telegramUserId: Long, authDate: Instant = clock.now()): String =
    InitDataSuite.signHmac(
      BotToken,
      Map(
        "auth_date" -> authDate.getEpochSecond.toString,
        "query_id" -> s"launch-${launches.incrementAndGet()}",
        "user" -> s"""{"id":$telegramUserId,"first_name":"Test"}"""
      )
    )

  protected def exchange(initData: String): Reply =
    post("/api/v1/session", ujson.Obj("initData" -> initData))

  protected def login(account: TelegramAccount): String =
    val reply = exchange(initDataFor(account.telegramUserId))
    assertEquals(reply.status, 201, reply.body)
    reply.json("token").str

  // ---------- HTTP ----------

  protected def get(path: String, token: String = ""): Reply = send("GET", path, token, None, Map.empty)

  protected def delete(path: String, token: String = ""): Reply = send("DELETE", path, token, None, Map.empty)

  protected def post(
      path: String,
      body: ujson.Value,
      token: String = "",
      headers: Map[String, String] = Map.empty
  ): Reply = send("POST", path, token, Some(ujson.write(body)), headers)

  protected def patch(path: String, body: ujson.Value, token: String, headers: Map[String, String] = Map.empty): Reply =
    send("PATCH", path, token, Some(ujson.write(body)), headers)

  protected def send(
      method: String,
      path: String,
      token: String,
      body: Option[String],
      headers: Map[String, String]
  ): Reply =
    val publisher = body.fold(HttpRequest.BodyPublishers.noBody())(HttpRequest.BodyPublishers.ofString)
    val builder = HttpRequest.newBuilder(URI(s"http://127.0.0.1:${bound.get()}$path")).method(method, publisher)
    if body.isDefined then builder.header("Content-Type", "application/json")
    if token.nonEmpty then builder.header("Authorization", s"Bearer $token")
    headers.foreach((name, value) => builder.header(name, value))
    val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    val names = response.headers().map()
    val all = names.keySet().toArray(Array.empty[String]).toList
    Reply(
      response.statusCode(),
      all.map(name => name.toLowerCase -> response.headers().firstValue(name).toScala.toList).toMap,
      response.body()
    )

  protected def newKey(): Map[String, String] = Map("Idempotency-Key" -> UUID.randomUUID().toString)

  protected def ifMatch(reply: Reply): Map[String, String] = Map("If-Match" -> reply.header("ETag").get)

  protected def assertProblem(reply: Reply, status: Int, code: String)(using munit.Location): Unit =
    assertEquals(reply.status, status, reply.body)
    assertEquals(reply.header("Content-Type"), Some("application/problem+json"))
    assertEquals(reply.code, code)
    assertEquals(reply.json("status").num.toInt, status)
    assert(reply.json("requestId").str.nonEmpty)

object ApiSuite:
  val T0: Instant = Instant.parse("2026-11-13T08:00:00Z")
  val BotToken = "7000000001:AAFakeTokenForTestsOnly_0123456789abcde"

  /** The signing key of the buttons the export routes put into the chat. */
  val Codec: CallbackCodec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 3).toByte)), None))

  /** A stand-in for the renderer's asset, which the composition root converts: the route only serves it. */
  val BlobStates: MoodBlobStates = MoodBlobStates(
    "test-1",
    List(
      BlobState(
        id = "blob.none",
        outline = List(1.0, 0.0, 1.0, 0.5, 0.5, 1.0, 0.0, 1.0),
        outlineWidth = 0.02,
        isDotted = true,
        gradient = Nil,
        ground = List(0.0, 1.18, 0.65, 0.03),
        light = BlobColours("#d8dfe3", "#d8dfe3", "#5e6e78", "#cdd6db", "#d8dfe3"),
        dark = BlobColours("#23363f", "#23363f", "#8b9ba3", "#2f434d", "#23363f")
      )
    )
  )

  final case class TelegramAccount(accountId: UUID, telegramUserId: Long)
