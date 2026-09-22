package dosecord.infra.db

import dosecord.contracts.*
import dosecord.core.application.CommandRegistry
import dosecord.core.application.FirstFlows
import dosecord.core.chat.*
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.ports.Clock

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** M0.12d acceptance on Testcontainers Postgres 18 (the core in-memory half is `FirstFlowsSuite`): the `/start` create
  * flow writes `users` (display_name NULL, never the vendor nickname), links `platform_identities` and inserts the
  * `delivery_channels` row in one transaction with `dosecord.identity.account_created.v1`; `/mood` rows store the note
  * only when supplied with trailing `#tags` in `tags text[]` and append `dosecord.mood.checkin_recorded.v1`; an invalid
  * zone and a second create write nothing.
  */
class FirstFlowsPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  // The vendor supplies a display name fixture; users.display_name must never copy it (K8).
  private val actor = PlatformIdentity("fake", "user-1", Some("VendorNick123"))

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, mood_checkins, delivery_channels, " +
            "platform_identities, users CASCADE"
        )
      finally st.close()
    }

  private final class FixedClock(at: Instant) extends Clock:
    override def now(): Instant = at

  private final class SyncAdapter(val inner: FakeAdapter) extends ChatAdapter:
    private def around[A](f: => A): A = inner.synchronized(f)
    override def vendor: String = inner.vendor
    override def capabilities: CapabilityProfile = inner.capabilities
    override def start(sink: InboundSink, resumeFrom: Option[String]): Unit = around(inner.start(sink, resumeFrom))
    override def stop(): Unit = around(inner.stop())
    override def send(chat: ChatRef, rendered: RenderedMessage, sendKey: String): MessageHandle = around(
      inner.send(chat, rendered, sendKey)
    )
    override def edit(handle: MessageHandle, rendered: RenderedMessage): MessageHandle = around(
      inner.edit(handle, rendered)
    )
    override def delete(handle: MessageHandle): Unit = around(inner.delete(handle))
    override def react(handle: MessageHandle, emoji: String, on: Boolean, txnKey: String): Unit = around(
      inner.react(handle, emoji, on, txnKey)
    )
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

  private final class Rig(using ox.Ox):
    val uow = PgUnitOfWork(dataSource, FixedClock(t0))
    val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
    val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
    val mediator = ChatMediator(
      uow,
      adapters,
      codec,
      FirstFlows.handler(uow, adapters, codec, FixedClock(t0)),
      FixedClock(t0),
      commands = CommandRegistry.byName
    )
    private var seq = 0

    /** Feeds one console-grammar line through the mediator and returns the newly printed chunks. */
    def line(input: String): List[String] =
      seq += 1
      val body =
        if input.length > 1 && input.startsWith("/") then
          Inbound.CommandInvoked(input.drop(1).takeWhile(c => !c.isWhitespace), Map.empty, input)
        else Inbound.MessageReceived(input, None, truncated = false)
      val event = InboundEvent(
        EventId(UUID.randomUUID()),
        "fake",
        s"fake:msg:pg:$seq",
        t0,
        actor = actor,
        chat = ChatRef("fake", "dm:user-1"),
        body = body
      )
      val before = sends.size
      mediator.push(event)
      await(sends.size > before, s"'$input' produced a reply")
      sends.drop(before).flatMap(_.message.chunks)

    def sends: List[VendorOp.Send] =
      adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(20)
      ok = cond
    assert(ok, clue)

  private def countSql(query: String): Long = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()
  }

  private final case class UserRow(id: UUID, handle: Option[String], displayName: Option[String], timezone: String)
  private given RowMapper[UserRow] = rs =>
    UserRow(rs.uuid("id"), rs.optString("handle"), rs.optString("display_name"), rs.getString("timezone"))

  private final case class MoodRow(note: Option[String], tags: List[String], level: Int)
  private given RowMapper[MoodRow] = rs =>
    MoodRow(rs.optString("note"), rs.stringArray("tags").toList, rs.getInt("mood_level"))

  // Acceptance 1: /start -> timezone pick -> confirm -> /mood 8 slept well #sleep -> one row, note + tags.
  // Acceptance 3: an invalid zone re-shows the picker and writes no row.
  // Acceptance 5: users.display_name is NULL and never the vendor nickname.
  // Acceptance 6: one account_created.v1 and one checkin_recorded.v1 with account_id = principal.
  test("create flow, mood with note+tags, mood bare, domain events"):
    ox.supervised:
      val rig = Rig()

      val picker = rig.line("/start")
      assert(picker.exists(_.contains("Pick your timezone:")), s"picker shown: $picker")
      assert(picker.exists(_.contains("Europe/Kyiv")), "the picker lists zones grouped by current offset")

      val customPrompt = rig.line("22") // Other — type your timezone
      assert(customPrompt.exists(_.contains(IdentityCopy.timezoneCustomPrompt)), s"text step: $customPrompt")

      // Acceptance 3: an invalid zone re-shows the picker and writes no row.
      val repicker = rig.line("Mars/Olympus_Mons")
      assert(repicker.exists(_.contains(IdentityCopy.invalidZone)), s"the note is shown: $repicker")
      assert(repicker.exists(_.contains("Pick your timezone:")), "the picker is re-shown")
      assertEquals(countSql("SELECT count(*) FROM users"), 0L, "no row for an invalid zone")
      assertEquals(countSql("SELECT count(*) FROM domain_events"), 0L, "no event for an invalid zone")

      val confirm = rig.line("13") // UTC+03:00 — Europe/Kyiv
      assert(confirm.exists(_.contains("It is 03:00 for you now, right?")), s"confirm echo: $confirm")

      val welcome = rig.line("1") // Yes
      assert(welcome.exists(_.contains(IdentityCopy.accountCreated)), s"welcome: $welcome")

      // The create transaction: users + platform_identities link + delivery_channels.
      val users = withConnection { conn =>
        given Connection = conn
        sql"SELECT id, handle, display_name, timezone FROM users".query[UserRow]()
      }
      assertEquals(users.size, 1, "one users row")
      val user = users.head
      assertEquals(user.timezone, "Europe/Kyiv")
      assertEquals(user.displayName, None, "display_name stays NULL")
      assertEquals(user.handle, None, "the generated handle is asked lazily (M4.1)")
      assert(!user.displayName.contains("VendorNick123"), "never the vendor nickname (K8)")
      withConnection { conn =>
        given Connection = conn
        val linked =
          sql"""SELECT count(*) FROM platform_identities
                WHERE user_id = ${user.id} AND vendor = 'fake' AND vendor_user_id = 'user-1'
                  AND linked_at IS NOT NULL""".queryOne[Long]().get
        assertEquals(linked, 1L, "the platform identity is linked")
        val channels =
          sql"""SELECT count(*) FROM delivery_channels
                WHERE account_id = ${user.id} AND role = 'primary' AND state = 'healthy'"""
            .queryOne[Long]().get
        assertEquals(channels, 1L, "the delivery channel row exists")
      }

      // Acceptance 1: /mood 8 slept well #sleep -> one row with note 'slept well' and tags {sleep}.
      val moodReply = rig.line("/mood 8 slept well #sleep")
      assert(moodReply.exists(_.contains("Mood recorded: 8/10.")), s"mood ack: $moodReply")
      // Acceptance 2: /mood 8 -> note NULL, tags {}.
      rig.line("/mood 8")

      val moods = withConnection { conn =>
        given Connection = conn
        sql"SELECT note, tags, mood_level FROM mood_checkins ORDER BY note NULLS LAST".query[MoodRow]()
      }
      assertEquals(moods, List(MoodRow(Some("slept well"), List("sleep"), 8), MoodRow(None, Nil, 8)))

      // Acceptance 6: domain events with account_id = the created account (the mediator re-resolves after the create).
      withConnection { conn =>
        given Connection = conn
        val events = sql"SELECT type, source, account_id FROM domain_events"
          .query[(String, String, UUID)]()
        // occurred_at is the fixed clock for all rows, so compare as a multiset.
        assertEquals(
          events.map((t, s, _) => (t, s)).groupBy(identity).view.mapValues(_.size).toMap,
          Map(
            (Event.AccountCreatedType, "dosecord.identity") -> 1,
            (Event.MoodCheckinRecordedType, "dosecord.mood") -> 2
          )
        )
        events.foreach((_, _, account) => assertEquals(account, user.id, "account_id = principal"))
        // The stored reply of the confirming event carries the created account id too (C2).
        val confirmAccount =
          sql"SELECT account_id FROM inbound_events WHERE vendor_event_id = 'fake:msg:pg:5'".queryOne[UUID]()
        assertEquals(confirmAccount, Some(user.id), "the confirm event's stored reply carries the new account")
      }

  private given RowMapper[(String, String, UUID)] = rs =>
    (rs.getString("type"), rs.getString("source"), rs.uuid("account_id"))

  // Acceptance 4: a second create from an already-linked identity replies with the catalogue error and writes no row.
  test("a second create is refused with the catalogue error and writes nothing"):
    ox.supervised:
      val rig = Rig()
      rig.line("/start")
      rig.line("13")
      rig.line("1")
      assertEquals(countSql("SELECT count(*) FROM users"), 1L)

      val refused = rig.line("/start")
      assert(refused.exists(_.contains(IdentityCopy.alreadyLinked)), s"catalogue error: $refused")
      assertEquals(countSql("SELECT count(*) FROM users"), 1L, "no second account")
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 0L, "no session row written")
      assertEquals(
        countSql(s"SELECT count(*) FROM domain_events WHERE type = '${Event.AccountCreatedType}'"),
        1L,
        "still one account_created event"
      )

  // Acceptance 7: /help lists every registered command including itself, rendered from the CommandSpec registry.
  test("/help lists every registered command including itself"):
    ox.supervised:
      val rig = Rig()
      rig.line("/start")
      rig.line("13")
      rig.line("1")
      val help = rig.line("/help")
      CommandRegistry.all.foreach: spec =>
        assert(help.exists(_.contains(s"/${spec.name}")), s"/help lists /${spec.name}")
      assert(help.exists(_.contains("/help")), "/help lists itself")
end FirstFlowsPgSuite
