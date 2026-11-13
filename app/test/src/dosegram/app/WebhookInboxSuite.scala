package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.adapter.telegram.TelegramAdapter
import dosegram.core.domain.copy.AdapterCopy
import dosegram.core.ports.InboxEntry
import dosegram.core.ports.InboundInbox
import dosegram.core.ports.InboxPurge
import dosegram.core.ports.PendingInboxEntry
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.TelegramWebhookSettings
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import ox.supervised

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.net.ConnectException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.Connection
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import scala.concurrent.duration.DurationInt

/** ROADMAP G1.7 without the live step: the webhook on the HTTP edge and the durable inbox, in the application of
  * [[Launch.start]] on Testcontainers Postgres with the Telegram adapter on the fake wire. Requests go over a real
  * socket to the listener; the crash tests stop one launched application and start another on the same database.
  */
class WebhookInboxSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private val t0 = Instant.parse("2026-11-13T09:00:00Z")
  private val owner = 7001L
  private val stranger = 9999L
  private val pathSecret = "path-secret-0123456789"
  private val headerSecret = "header-secret-0123456789"
  private val client = HttpClient.newHttpClient()

  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100)
  )

  override def beforeEach(context: BeforeEach): Unit =
    withConnection: conn =>
      conn
        .createStatement()
        .execute(
          "TRUNCATE inbound_inbox, inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, delivery_channels, platform_identities, " +
            "worker_heartbeat, users CASCADE"
        )
      ()

  // ---------- The launched application ----------

  private def databaseUrl: String =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}"

  private val webhook = TelegramWebhookSettings(s"https://bot.example/tg/webhook/$pathSecret", pathSecret, headerSecret)

  private val telegram = TelegramSettings(
    "555000111:fake-wire-token",
    FakeTelegramTransport.Bot.id,
    TelegramUpdates.Webhook,
    Set(owner),
    Some(webhook)
  )

  private def settings(port: Int): Settings =
    Settings(
      enabledAdapters = List(Adapter.Telegram),
      databaseUrl = databaseUrl,
      callbackKeys = Settings.parseCallbackKeys("0:MDEyMzQ1Njc4OWFiY2RlZg").fold(error => fail(error), identity),
      role = Role.All,
      instanceId = "webhook-inbox",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = Some("https://bot.example"),
      healthPort = freePort(),
      environment = Environment.Development,
      httpPort = port
    )

  private final class Bot(val wire: FakeTelegramTransport, val port: Int, val adapter: TelegramAdapter):
    def sends: List[FakeTelegramTransport.RecordedCall] = wire.calls("sendMessage")

    def post(body: String, path: String = pathSecret, header: Option[String] = Some(headerSecret)): Int =
      val request = HttpRequest
        .newBuilder(URI.create(s"http://localhost:$port/tg/webhook/$path"))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
      header.foreach(request.header("X-Telegram-Bot-Api-Secret-Token", _))
      client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()

  /** Runs `test` against a launched application whose inbox is `inbox` over this suite's database. */
  private def launched[A](inbox: DataSource => InboundInbox = PgInboundInbox(_), lastMessageId: Long = 0L)(
      test: Bot => A
  ): A =
    val clock = SteppedClock(t0)
    val wire = FakeTelegramTransport(clock, lastMessageId = lastMessageId)
    val port = freePort()
    supervised:
      val config = settings(port)
      val adapter = TelegramAssembly.adapter(config, telegram, wire, clock)(StatusImages.textOnly, Some(inbox(dataSource)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), fast), log = _ => ())
      awaitListener(port)
      test(Bot(wire, port, adapter))

  private def awaitListener(port: Int): Unit =
    eventually("the listener accepts connections"):
      try
        client.send(
          HttpRequest.newBuilder(URI.create(s"http://localhost:$port/")).GET().build(),
          HttpResponse.BodyHandlers.discarding()
        )
        true
      catch case _: ConnectException => false

  // ---------- Updates and observation ----------

  private var updateSeq = 800L

  private def text(userId: Long, body: String): (Long, String) =
    updateSeq += 1
    val update = ujson.Obj(
      "update_id" -> updateSeq.toDouble,
      "message" -> ujson.Obj(
        "message_id" -> (updateSeq + 9000).toDouble,
        "from" -> ujson.Obj("id" -> userId.toDouble, "is_bot" -> false, "first_name" -> "CANARY-NAME"),
        "chat" -> ujson.Obj("id" -> userId.toDouble, "type" -> "private"),
        "date" -> t0.getEpochSecond.toDouble,
        "text" -> body
      )
    )
    (updateSeq, ujson.write(update))

  private def eventually(clue: => String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + 60.seconds.toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 60 s: $clue")

  private def settle(): Unit = Thread.sleep(700)

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  private def inboxStatus(updateId: Long): List[String] =
    strings(s"SELECT status FROM inbound_inbox WHERE vendor_event_id = 'telegram:update:$updateId'")

  private def inboundEvents: Int = strings("SELECT vendor_event_id FROM inbound_events").size

  private def replies: Int = strings("SELECT id::text FROM outbox_messages WHERE kind = 'interaction_reply'").size

  // ---------- The endpoint ----------

  test("start-up registers the webhook and leaves it in place: no deleteWebhook"):
    launched(): bot =>
      assertEquals(bot.wire.calls("setWebhook").map(_.text("url")), List(Some(webhook.url)))
      assertEquals(bot.wire.calls("setWebhook").map(_.text("secret_token")), List(Some(headerSecret)))
      assertEquals(bot.wire.calls("setWebhook").map(_.number("max_connections")), List(Some(1L)))
      assertEquals(bot.wire.calls("deleteWebhook"), Nil)

  test("an update with both secrets is stored, answered 200, then processed once and replied to"):
    launched(): bot =>
      val (updateId, body) = text(owner, "/help")
      assertEquals(bot.post(body), 200)
      assert(inboxStatus(updateId).nonEmpty, "stored before the 200")
      eventually("the update is processed")(inboxStatus(updateId) == List("processed"))
      eventually("the reply is sent")(bot.sends.nonEmpty)
      assertEquals(inboundEvents, 1)
      assertEquals(strings("SELECT 1 FROM inbound_inbox WHERE body IS NOT NULL").size, 0, "the body is gone")

  test("a wrong or missing secret is 404 and stores nothing, even with a body over the limit"):
    launched(): bot =>
      val (_, body) = text(owner, "/help")
      assertEquals(bot.post(body, path = "path-secret-guessed000"), 404)
      assertEquals(bot.post(body, header = Some("header-secret-guessed00")), 404)
      assertEquals(bot.post(body, header = None), 404)
      assertEquals(bot.post("x" * (2 * 1024 * 1024)), 413)
      assertEquals(bot.post("x" * (2 * 1024 * 1024), header = Some("wrong-secret-0000000000")), 404)
      settle()
      assertEquals(strings("SELECT 1 FROM inbound_inbox").size, 0)

  test("TG-04: the same update delivered twice is one row and one effect"):
    launched(): bot =>
      val (updateId, body) = text(owner, "/help")
      assertEquals(bot.post(body), 200)
      eventually("the update is processed")(inboxStatus(updateId) == List("processed"))
      val replied = replies
      assertEquals(bot.post(body), 200)
      settle()
      assertEquals(inboxStatus(updateId), List("processed"))
      assertEquals(inboundEvents, 1)
      assertEquals(replies, replied)

  test("an update from a user outside the allowlist is answered 200, gets the fixed reply and stores nothing"):
    launched(): bot =>
      val (_, body) = text(stranger, "/start")
      assertEquals(bot.post(body), 200)
      eventually("the fixed reply is sent")(bot.sends.nonEmpty)
      assertEquals(bot.sends.map(_.text("text")), List(Some(AdapterCopy.notAvailable)))
      List("inbound_inbox", "inbound_events", "platform_identities", "users").foreach: table =>
        assertEquals(strings(s"SELECT 1 FROM $table").size, 0, s"$table must stay empty")

  test("the stored body keeps no name the update carried"):
    launched(inbox = HeldInbox(_)): bot =>
      val (updateId, body) = text(owner, "/help")
      assertEquals(bot.post(body), 200)
      val stored = strings(s"SELECT body FROM inbound_inbox WHERE vendor_event_id = 'telegram:update:$updateId'")
      assertEquals(stored.size, 1)
      assert(!stored.head.contains("CANARY-NAME"), stored.head)

  // ---------- Crash injection ----------

  test("TG-03: with a crash between receipt and commit the endpoint answers 503, never 200, and nothing is stored"):
    val crash = AtomicBoolean(true)
    launched(inbox = dataSource => PgInboundInbox(CrashBeforeCommit(dataSource, crash))): bot =>
      val (updateId, body) = text(owner, "/help")
      assertEquals(bot.post(body), 503)
      assertEquals(inboxStatus(updateId), Nil, "the insert was rolled back")
      crash.set(false)
      assertEquals(bot.post(body), 200, "Telegram's retry is stored")
      eventually("the retry is processed")(inboxStatus(updateId) == List("processed"))
      assertEquals(inboundEvents, 1)

  test("TG-03: killed between store and processing, the restarted process applies the update exactly once"):
    val (updateId, body) = text(owner, "/help")
    val sentBefore = launched(inbox = HeldInbox(_)): bot =>
      assertEquals(bot.post(body), 200)
      settle()
      assertEquals(inboxStatus(updateId), List("pending"))
      assertEquals(inboundEvents, 0, "nothing was processed before the crash")
      bot.sends.size
    assertEquals(sentBefore, 0)
    launched(): bot =>
      eventually("the restarted process processes the stored update")(inboxStatus(updateId) == List("processed"))
      eventually("the reply is sent")(bot.sends.nonEmpty)
      val replied = replies
      settle()
      assertEquals(inboundEvents, 1)
      assertEquals(replies, replied)
      assertEquals(bot.sends.size, 1)

  test("TG-08: killed between the mediator's commit and the inbox mark, the restart changes nothing a second time"):
    val (updateId, body) = text(owner, "/help")
    val adapterOf = AtomicReference(Option.empty[TelegramAdapter])
    val repliedBefore = launched(inbox = dataSource => KilledAtMark(PgInboundInbox(dataSource), adapterOf)): bot =>
      adapterOf.set(Some(bot.adapter))
      assertEquals(bot.post(body), 200)
      eventually("the mediator committed")(inboundEvents == 1)
      settle()
      assertEquals(inboxStatus(updateId), List("pending"), "the mark was lost with the process")
      replies
    launched(lastMessageId = 100L): _ =>
      eventually("the restarted process marks the update")(inboxStatus(updateId) == List("processed"))
      settle()
      assertEquals(inboundEvents, 1, "one inbound event")
      assertEquals(replies, repliedBefore, "the handler did not run again: no new reply row")

  test("ARCHITECTURE section 15.6: the worker half runs the deleting job of the inbound stores"):
    val inbox = PgInboundInbox(dataSource)
    inbox.store(InboxEntry("telegram", "telegram:update:1", "7001", 1L, "{}"), t0.minus(Duration.ofDays(8)))
    launched(): _ =>
      eventually("the 7-day-old row is deleted")(strings("SELECT 1 FROM inbound_inbox").isEmpty)

/** Stores like the real inbox and offers nothing: the process dies before its processor takes a stored update. */
private final class HeldInbox(dataSource: DataSource) extends InboundInbox:
  private val store = PgInboundInbox(dataSource)
  export store.{pending as _, *}
  override def pending(perChat: Int, limit: Int): List[PendingInboxEntry] = Nil

/** The real inbox, except that the first mark ends the processor and is lost, as if the process died at that point. */
private final class KilledAtMark(store: PgInboundInbox, adapter: AtomicReference[Option[TelegramAdapter]])
    extends InboundInbox:
  export store.{markProcessed as _, *}
  override def markProcessed(entry: InboxEntry, at: Instant): Unit =
    adapter.get().flatMap(_.inboxProcessor).foreach(_.stop())
    throw SQLException("injected: the process died before the mark")

/** A data source whose connections, while `crash` is set, run their statements in a transaction that is rolled back
  * when the connection is returned, and then fail: the process died between the insert and its commit.
  */
private object CrashBeforeCommit:
  def apply(dataSource: DataSource, crash: AtomicBoolean): DataSource =
    val handler: InvocationHandler = (_, method, args) =>
      if method.getName == "getConnection" && crash.get() then crashing(dataSource.getConnection)
      else invoke(dataSource, method, args)
    Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[DataSource]), handler).asInstanceOf[DataSource]

  private def crashing(connection: Connection): Connection =
    connection.setAutoCommit(false)
    val handler: InvocationHandler = (_, method, args) =>
      if method.getName == "close" then
        connection.rollback()
        connection.setAutoCommit(true)
        connection.close()
        throw SQLException("injected: the process died before the commit")
      else invoke(connection, method, args)
    Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), handler).asInstanceOf[Connection]

  private def invoke(target: AnyRef, method: Method, args: Array[AnyRef]): AnyRef =
    try method.invoke(target, (if args == null then Array.empty[AnyRef] else args)*)
    catch case e: InvocationTargetException => throw e.getCause
