package dosegram.app

import dosegram.adapter.telegram.FakeTelegramTransport
import dosegram.adapter.telegram.FakeTelegramTransport.RecordedCall
import dosegram.adapter.telegram.WireReply
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.ports.ConsentPurpose
import dosegram.infra.Adapter
import dosegram.infra.Environment
import dosegram.infra.LogFormat
import dosegram.infra.LogLevel
import dosegram.infra.Role
import dosegram.infra.Settings
import dosegram.infra.TelegramSettings
import dosegram.infra.TelegramUpdates
import dosegram.infra.crypto.DataKeys
import dosegram.infra.db.PgInboundInbox
import dosegram.infra.db.PgSuite
import ox.fork
import ox.supervised

import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Leftover 1 of the verification of 2026-11-28: the messages of one answer reach the chat in their order under load.
  * Several people finish onboarding at once on the production assembly; the fake wire refuses message calls beyond
  * Telegram's per-chat rate with a 429, and the first try of each consent receipt with a 502. The receipt and the
  * closing message of onboarding are one answer, so each chat shows the receipt first.
  */
class ReplyOrderSuite extends PgSuite:

  override val munitTimeout = scala.concurrent.duration.Duration(240, "s")

  private val start = Instant.parse("2026-09-21T07:00:00Z")
  private val people = (9101L to 9108L).toList
  private val medication = ConsentPurpose.Medication
  private val receipt = ConsentCopy.receipt(medication, "21 Sep 2026")
  private val badGateway =
    WireReply.Http(502, ujson.write(ujson.Obj("ok" -> false, "error_code" -> 502, "description" -> "Bad Gateway")))

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute("SET session_replication_role = replica")
        val rs = st.executeQuery(
          "SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables " +
            "WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'"
        )
        rs.next()
        st.execute(s"TRUNCATE ${rs.getString(1)} CASCADE")
        st.execute("SET session_replication_role = DEFAULT")
      finally st.close()
    }

  private val fast = Workers.Cadence(
    reminderPoll = Duration.ofMillis(100),
    materialiserPeriod = Duration.ofMinutes(15),
    outboxIdlePoll = Duration.ofMillis(50),
    sessionSweepPeriod = Duration.ofMillis(100),
    rollupIdlePoll = Duration.ofMillis(100),
    imagePurgePeriod = Duration.ofMillis(100),
    ownerAlertPeriod = Duration.ofMillis(100)
  )

  private val telegram =
    TelegramSettings("555000111:fake-wire-token", FakeTelegramTransport.Bot.id, TelegramUpdates.Poll, people.toSet)

  private def settings: Settings =
    val separator = if pg.jdbcUrl.contains("?") then "&" else "?"
    Settings(
      enabledAdapters = List(Adapter.Telegram),
      databaseUrl = s"${pg.jdbcUrl}${separator}user=${pg.username}&password=${pg.password}",
      callbackKeys = Settings.parseCallbackKeys("0:MDEyMzQ1Njc4OWFiY2RlZg").fold(error => fail(error), identity),
      role = Role.All,
      instanceId = "reply-order",
      logLevel = LogLevel.Info,
      logFormat = LogFormat.Plain,
      consoleUserId = None,
      telegram = Some(telegram),
      publicBaseUrl = None,
      healthPort = freePort(),
      environment = Environment.Development
    )

  private def strings(query: String): List[String] = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query) // a query of this suite, never input
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
  }

  private def eventually(clue: => String)(condition: => Boolean): Unit =
    val deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos
    while !condition && System.nanoTime() < deadline do Thread.sleep(25)
    assert(condition, s"not within 90 s: $clue")

  /** Everyone's chats on one fake wire: each step is taken by every person at once. */
  private final class Crowd(wire: FakeTelegramTransport, clock: SteppedClock):
    private val updateIds = AtomicLong(1000)

    private def chatOf(call: RecordedCall): Option[Long] = call.number("chat_id")

    private def delivered(call: RecordedCall): Boolean = call.reply match
      case WireReply.Http(200, _) => true
      case _                      => false

    /** The messages the bot put in `person`'s chat, sent or edited, in the order they reached it. */
    def shown(person: Long): List[RecordedCall] =
      wire.calls.filter(call =>
        Set("sendMessage", "editMessageText")(call.method) && chatOf(call).contains(person) && delivered(call)
      )

    private def text(call: RecordedCall): String = call.text("text").getOrElse("")

    private def buttons(call: RecordedCall): List[(String, String)] =
      call.request.params.obj.get("reply_markup").toList
        .flatMap(_.obj.get("inline_keyboard").flatMap(_.arrOpt).toList.flatten)
        .flatMap(_.arr.toList)
        .flatMap(button => button.obj.get("callback_data").flatMap(_.strOpt).map(data => (button("text").str, data)))

    private def from(person: Long) = ujson.Obj("id" -> person.toDouble, "is_bot" -> false, "first_name" -> "Crowd")
    private def chat(person: Long) = ujson.Obj("id" -> person.toDouble, "type" -> "private")

    private def message(person: Long, typed: String): ujson.Value =
      val id = updateIds.incrementAndGet()
      ujson.Obj(
        "update_id" -> id.toDouble,
        "message" -> ujson.Obj(
          "message_id" -> (id + 900000).toDouble,
          "from" -> from(person),
          "chat" -> chat(person),
          "date" -> clock.now().getEpochSecond.toDouble,
          "text" -> typed
        )
      )

    private def tap(person: Long, matches: String => Boolean): ujson.Value =
      val (card, data) = shown(person).reverseIterator
        .flatMap(call => buttons(call).collectFirst { case (label, data) if matches(label) => (call, data) })
        .nextOption()
        .getOrElse(fail(s"no such button for $person: ${shown(person).map(text)}"))
      val messageId = card.sentMessageId.orElse(card.number("message_id")).getOrElse(fail("the card names no message"))
      val id = updateIds.incrementAndGet()
      ujson.Obj(
        "update_id" -> id.toDouble,
        "callback_query" -> ujson.Obj(
          "id" -> s"query-$id",
          "from" -> from(person),
          "message" -> ujson.Obj(
            "message_id" -> messageId.toDouble,
            "chat" -> chat(person),
            "date" -> clock.now().getEpochSecond.toDouble,
            "text" -> "x"
          ),
          "data" -> data
        )
      )

    /** Everyone sends `update` at once, then each chat shows `expected` in a message after it. */
    private def everyone(expected: String)(update: Long => ujson.Value): Unit =
      val before = people.map(person => person -> shown(person).size).toMap
      wire.enqueueUpdates(people.map(update)*)
      people.foreach: person =>
        eventually(s"$person sees '$expected'; got ${shown(person).drop(before(person)).map(text)}"):
          shown(person).drop(before(person)).exists(call => text(call).contains(expected))

    def say(typed: String, expected: String): Unit = everyone(expected)(message(_, typed))

    def tapLabel(label: String, expected: String): Unit = everyone(expected)(tap(_, _ == label))

    def tapWhere(matches: String => Boolean, expected: String): Unit = everyone(expected)(tap(_, matches))

    /** Where in `person`'s chat the first message that contains `words` is. */
    def positionOf(person: Long, words: String): Int = shown(person).indexWhere(call => text(call).contains(words))

  test("under load and a refused first try, the consent receipt of each chat comes before the closing message"):
    val clock = SteppedClock(start)
    val wire = FakeTelegramTransport(clock)
    wire.enforce(FakeTelegramTransport.FloodLimits(burst = 3, perSecond = 1.0, retryAfter = Duration.ofSeconds(2)))
    people.foreach: person =>
      wire.replyOnceWhen(
        request =>
          request.method == "sendMessage" && request.params.obj.get("chat_id").flatMap(_.numOpt).contains(person.toDouble) &&
            request.params.obj.get("text").flatMap(_.strOpt).exists(_.contains(receipt)),
        badGateway
      )
    val running = AtomicBoolean(true)
    supervised:
      val config = settings
      val adapter =
        TelegramAssembly.adapter(config, telegram, wire, clock)(StatusImages.textOnly, Some(PgInboundInbox(dataSource, DataKeys.development)))
      Launch.start(config, dataSource, Launch.Parts(clock, Map("telegram" -> adapter), fast), log = _ => ())
      // Time passes at about twenty times real time: the adapter paces each chat by the clock, and a refused send is
      // tried again from the outbox 30 seconds later.
      fork:
        while running.get() do
          clock.advance(Duration.ofMillis(200))
          Thread.sleep(10)
      try
        val crowd = Crowd(wire, clock)
        crowd.say("/start", ConsentCopy.notice.head)
        crowd.tapLabel(ConsentCopy.onboardingLabel(medication), ConsentCopy.statement(medication))
        crowd.tapLabel(ConsentCopy.Agree, ConsentCopy.confirmation(medication))
        crowd.tapLabel(ConsentCopy.Confirm, IdentityCopy.timezonePrompt)
        crowd.tapWhere(_.endsWith("— UTC"), "UTC")
        crowd.tapLabel(Labels.Yes, IdentityCopy.accountReadyMedication)
        people.foreach: person =>
          eventually(s"$person's receipt arrives"):
            crowd.positionOf(person, receipt) >= 0
          val (first, closing) = (crowd.positionOf(person, receipt), crowd.positionOf(person, IdentityCopy.accountReadyMedication))
          assert(first < closing, s"$person read the closing message (#$closing) before the receipt (#$first)")
        val refused = wire.calls.count(_.reply == badGateway)
        assertEquals(refused, people.size, "each receipt was refused once")
        eventually(s"the outbox drains: ${strings("SELECT kind || ':' || status FROM outbox_messages WHERE status <> 'sent'")}"):
          strings("SELECT status FROM outbox_messages WHERE status IN ('queued', 'sending', 'failed_retry')").isEmpty
        assertEquals(strings("SELECT status FROM outbox_messages WHERE status IN ('dead', 'failed_permanent')"), Nil)
      finally running.set(false)
end ReplyOrderSuite
