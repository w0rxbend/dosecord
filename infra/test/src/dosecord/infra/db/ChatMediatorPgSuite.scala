package dosecord.infra.db

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.ports.Clock

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLTransientConnectionException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** M0.12a mediator acceptance on Testcontainers Postgres 18: idempotent replay, account-id propagation, the
  * resolution rules over real `rendered_messages`, the bad-MAC audit row, and the failure toast with the pool paused
  * (R68).
  */
class ChatMediatorPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val actor = PlatformIdentity("fake", "user-1", Some("display"))

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "platform_identities, users CASCADE"
        )
      finally st.close()
    }

  private def token(action: String, subject: UUID, value: Long = 0): String =
    codec.encode(CallbackMode.Direct, ActionRegistry.byName(action).get, subject, value).wire

  private def callbackRef(raw: String): CallbackRef =
    val payload = codec.decode(raw).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, raw)

  private def tamperedRef(raw: String): CallbackRef =
    val payload = codec.decode(raw).toOption.get
    val tampered = raw.updated(10, if raw(10) == 'A' then 'B' else 'A')
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, tampered)

  private def event(
      body: Inbound,
      vendorEventId: String,
      chatId: String = "chat-1",
      interaction: Option[InteractionHandle] = None
  ): InboundEvent =
    InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      vendorEventId,
      t0,
      actor = actor,
      chat = ChatRef("fake", chatId),
      principal = None,
      body = body,
      interaction = interaction
    )

  private def outbound(text: String, dedupeKey: String): OutboundMessage =
    OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text(text)))),
      dedupeKey = dedupeKey,
      correlationId = s"c-$dedupeKey"
    )

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(20)
      ok = cond
    assert(ok, clue)

  private final class RecordingInteractionHandle extends InteractionHandle:
    val calls = new ConcurrentLinkedQueue[String]()
    @volatile private var ackedFlag = false
    override def deferUpdate(): Unit =
      calls.add("deferUpdate")
      ackedFlag = true
    override def deferReply(ephemeral: Boolean): Unit =
      calls.add(s"deferReply($ephemeral)")
      ackedFlag = true
    override def answer(toast: Option[String]): Unit =
      calls.add(s"answer(${toast.getOrElse("")})")
      ackedFlag = true
    override def respond(rendered: RenderedMessage): MessageHandle =
      calls.add(s"respond(${rendered.chunks.mkString(" ")})")
      MessageHandle("fake", "chat-1", "r1")
    override def editSource(rendered: RenderedMessage): Unit =
      calls.add(s"editSource(${rendered.chunks.mkString(" ")})")
    override def openForm(form: RenderedForm): Unit =
      calls.add(s"openForm(${form.id})")
      ackedFlag = true
    override def acked: Boolean = ackedFlag

  private final class RecordingHandler(reply: InboundEvent => Reply) extends ChatHandler:
    val received = new ConcurrentLinkedQueue[(InboundEvent, Principal)]()
    def calls: List[(InboundEvent, Principal)] = received.asScala.toList
    override def handle(event: InboundEvent, principal: Principal, tx: dosecord.core.ports.Tx): Reply =
      received.add((event, principal))
      reply(event)

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

  private def sendsOf(adapter: SyncAdapter): List[VendorOp.Send] =
    adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

  /** Simulates the connection pool being paused: every checkout fails (R68). */
  private final class PausableDataSource(inner: DataSource) extends DataSource:
    @volatile var paused = false
    override def getConnection(): Connection =
      if paused then throw new SQLTransientConnectionException("pool is paused")
      else inner.getConnection
    override def getConnection(username: String, password: String): Connection =
      if paused then throw new SQLTransientConnectionException("pool is paused")
      else inner.getConnection(username, password)
    override def getLogWriter: PrintWriter = inner.getLogWriter
    override def setLogWriter(out: PrintWriter): Unit = inner.setLogWriter(out)
    override def setLoginTimeout(seconds: Int): Unit = inner.setLoginTimeout(seconds)
    override def getLoginTimeout: Int = inner.getLoginTimeout
    override def getParentLogger: Logger = inner.getParentLogger
    override def unwrap[T](iface: Class[T]): T = inner.unwrap(iface)
    override def isWrapperFor(iface: Class[?]): Boolean = inner.isWrapperFor(iface)

  private def seedLinkedAccount(): UUID =
    val userId = UUID.randomUUID()
    withConnection { conn =>
      given Connection = conn
      sql"INSERT INTO users (id, status) VALUES ($userId, 'active')".execute()
      sql"""INSERT INTO platform_identities (id, user_id, vendor, vendor_user_id)
            VALUES (${UUID.randomUUID()}, $userId, 'fake', 'user-1')""".execute()
    }
    userId

  private def countSql(query: String): Long = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()
  }

  private def mediator(
      uow: PgUnitOfWork,
      adapter: SyncAdapter,
      handler: ChatHandler
  )(using ox.Ox): ChatMediator =
    ChatMediator(uow, Map("fake" -> adapter), codec, handler, Clock.system)

  // Acceptance 1: the same vendor_event_id twice yields one row and the replayed reply.
  test("duplicate vendor_event_id yields one inbound_events row and the replayed reply"):
    ox.supervised:
      seedLinkedAccount()
      val uow = PgUnitOfWork(dataSource, Clock.system)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply(followUps = List(outbound("hello", "reply-1"))))
      val m = mediator(uow, adapter, handler)
      val first = event(Inbound.MessageReceived("hi", None, truncated = false), "evt-dup")

      m.push(first)
      await(sendsOf(adapter).size == 1, "first reply delivered")
      m.push(first)
      await(sendsOf(adapter).size == 2, "stored reply replayed")

      assertEquals(handler.calls.size, 1, "the handler ran exactly once")
      assertEquals(countSql("SELECT count(*) FROM inbound_events WHERE vendor = 'fake' AND vendor_event_id = 'evt-dup'"), 1L)
      assertEquals(sendsOf(adapter).map(_.message.chunks), List(List("hello"), List("hello")))

  // Acceptance 5: every stored reply and every domain_events row carries the resolved account_id; exactly one
  // domain_events row with the expected type and source.
  test("stored reply and domain_events row carry the resolved account id, type and source"):
    ox.supervised:
      val accountId = seedLinkedAccount()
      val uow = PgUnitOfWork(dataSource, Clock.system)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val domainEvent = Event.MoodCheckinRecorded(AccountId(accountId), MoodLevel.unsafe(8), None, Nil)
      val handler = RecordingHandler(_ =>
        Reply(followUps = List(outbound("logged", "reply-mood")), domainEvents = List(domainEvent))
      )
      val m = mediator(uow, adapter, handler)

      m.push(event(Inbound.CommandInvoked("mood", Map("level" -> "8"), "/mood 8"), "evt-mood"))
      await(sendsOf(adapter).size == 1, "reply delivered")
      await(
        countSql(
          "SELECT count(*) FROM inbound_events WHERE vendor_event_id = 'evt-mood' AND processed_at IS NOT NULL"
        ) == 1L,
        "processed"
      )

      withConnection { conn =>
        given Connection = conn
        val storedAccount =
          sql"SELECT account_id FROM inbound_events WHERE vendor = 'fake' AND vendor_event_id = 'evt-mood'"
            .queryOne[UUID]()
        assertEquals(storedAccount, Some(accountId), "the stored reply carries the resolved account_id")
        val events =
          sql"SELECT type, source, account_id FROM domain_events".query[(String, String, UUID)]()
        assertEquals(events.size, 1, "exactly one domain_events row")
        assertEquals(
          events.head,
          (Event.MoodCheckinRecordedType, "dosecord.mood", accountId),
          "type, source and account_id"
        )
      }

  private given RowMapper[(String, String, UUID)] = rs =>
    (rs.getString("type"), rs.getString("source"), rs.uuid("account_id"))

  // Acceptance 6: quoted and bare "2" resolve to the same token when the prompt is the latest; only the quoted one
  // resolves against it when it is not.
  test("quoted reply resolves by target; bare digit against the latest pending prompt only"):
    ox.supervised:
      seedLinkedAccount()
      val uow = PgUnitOfWork(dataSource, Clock.system)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)

      def prompt(handle: MessageHandle, first: String, second: String, sentAt: Instant): Unit =
        uow.transaction(
          _.renderedMessages.record(
            handle,
            None,
            "prompt",
            None,
            None,
            None,
            List(
              ChoiceMapEntry("cs", 1, "one", None, first),
              ChoiceMapEntry("cs", 2, "two", None, second)
            ),
            sentAt
          )
        )

      val handleA = MessageHandle("fake", "chat-1", "mA")
      val tokenA2 = token("menu.open", UUID.randomUUID())
      prompt(handleA, token("menu.open", UUID.randomUUID()), tokenA2, t0)

      def resolvedCallback(vendorEventId: String): CallbackRef =
        await(handler.calls.exists(_._1.vendorEventId == vendorEventId), s"$vendorEventId handled")
        handler.calls.collectFirst:
          case (e, _) if e.vendorEventId == vendorEventId =>
            e.body.asInstanceOf[Inbound.InteractionSubmitted].callback
        .get

      m.push(event(Inbound.MessageReceived("2", None, truncated = false), "bare-1"))
      m.push(event(Inbound.MessageReceived("2", Some(handleA), truncated = false), "quoted-1"))
      assertEquals(resolvedCallback("bare-1"), callbackRef(tokenA2))
      assertEquals(resolvedCallback("quoted-1"), callbackRef(tokenA2))

      val handleB = MessageHandle("fake", "chat-1", "mB")
      val tokenB2 = token("menu.open", UUID.randomUUID())
      prompt(handleB, token("menu.open", UUID.randomUUID()), tokenB2, t0.plusSeconds(60))

      m.push(event(Inbound.MessageReceived("2", None, truncated = false), "bare-2"))
      m.push(event(Inbound.MessageReceived("2", Some(handleA), truncated = false), "quoted-2"))
      assertEquals(resolvedCallback("bare-2"), callbackRef(tokenB2), "bare digits hit the latest prompt only")
      assertEquals(resolvedCallback("quoted-2"), callbackRef(tokenA2), "quoted replies resolve by target")

  // Acceptance 4: a tampered token yields the toast and an audit row and nothing reaches the core.
  test("a tampered token yields the toast and an auth_audit_log row; nothing reaches the core"):
    ox.supervised:
      seedLinkedAccount()
      val uow = PgUnitOfWork(dataSource, Clock.system)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Discord))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)
      val handle = RecordingInteractionHandle()

      m.push(
        event(
          Inbound.InteractionSubmitted(tamperedRef(token("dose.taken", UUID.randomUUID())), Nil, None),
          "evt-tampered",
          interaction = Some(handle)
        )
      )
      assert(
        handle.calls.asScala.exists(_.contains(ReminderCopy.staleControlToast)),
        "the catalogue toast was delivered"
      )
      assertEquals(
        countSql("SELECT count(*) FROM auth_audit_log WHERE kind = 'callback_tampered' AND outcome = 'rejected'"),
        1L,
        "one audit row"
      )
      assert(handler.calls.isEmpty, "nothing reaches the core")
      assertEquals(countSql("SELECT count(*) FROM inbound_events"), 0L, "no inbound row")

  // Acceptance 7: a tap with the connection pool paused yields the catalogue failure toast and leaves no
  // inbound_events row without processed_at (R68: visibly fails).
  test("a tap with the pool paused yields the catalogue failure toast and no unprocessed inbound row"):
    ox.supervised:
      seedLinkedAccount()
      val pausable = PausableDataSource(dataSource)
      val uow = PgUnitOfWork(pausable, Clock.system)
      val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Discord))
      val handler = RecordingHandler(_ => Reply.empty)
      val m = mediator(uow, adapter, handler)
      val handle = RecordingInteractionHandle()
      pausable.paused = true

      try
        m.push(
          event(
            Inbound.InteractionSubmitted(callbackRef(token("dose.taken", UUID.randomUUID())), Nil, None),
            "evt-paused",
            interaction = Some(handle)
          )
        )
        await(
          handle.calls.asScala.exists(_.contains(ReminderCopy.failureToast)),
          s"the failure toast was delivered: ${handle.calls.asScala.mkString}"
        )
        assertEquals(handle.calls.peek, "deferUpdate", "the ack landed before the failed database work")
        assertEquals(
          countSql("SELECT count(*) FROM inbound_events WHERE processed_at IS NULL"),
          0L,
          "no inbound_events row without processed_at"
        )
        assert(handler.calls.isEmpty, "nothing reached the handler")
      finally pausable.paused = false
