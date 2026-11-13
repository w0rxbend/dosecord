package dosegram.core.application

import dosegram.contracts.*
import dosegram.core.chat.*
import dosegram.core.chat.MediatorFakes.*
import dosegram.core.domain.copy.ConsentCopy
import ox.Ox

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import scala.io.Source
import scala.util.Using

/** M0.12d acceptance on `FakeAdapter(CapabilityProfiles.Console)` over the in-memory ports: the `/start` create flow
  * (timezone pick, invalid zone, confirm), `/help` from the registry, the refusal of `/mood`, and the
  * already-linked refusal — one transcript golden plus row-level assertions on the in-memory stores. The Postgres half
  * is `infra`'s `FirstFlowsPgSuite`; the real-console half is `adapter-console`'s `ConsoleFirstFlowsSuite`.
  */
class FirstFlowsSuite extends munit.FunSuite:

  test("first flows transcript (golden) and row-level effects"):
    ox.supervised:
      val rig = FirstFlowsRig()
      val transcript = rig.drive(FirstFlowsRig.Script)

      assertEquals(
        transcript,
        FirstFlowsRig.expectedGolden,
        "transcript drifted; regenerate with ./mill core.test.runMain dosegram.core.application.firstFlowsGoldenGenerate"
      )

      // One account, timezone captured, display name neutral (never the vendor nickname fixture, K8).
      assertEquals(rig.uow.accounts.all.size, 1, "exactly one account created")
      val account = rig.uow.accounts.all.head
      assertEquals(account.currentTimezone, "Europe/Kyiv")
      assertEquals(account.displayName, None)
      assert(
        account.displayName != FirstFlowsRig.actor.displayName,
        "display name never copies the vendor nickname"
      )

      // Acceptance 6: one account_created.v1 with account_id = created account, and no event of another domain.
      val events = rig.uow.domainEvents.all
      assertEquals(events.map(_.eventType), List(Event.AccountCreatedType))
      events.foreach: e =>
        assertEquals(e.accountId, Some(account.accountId), s"${e.eventType} carries the created account id")
        assertEquals(e.source, e.eventType.split('.').take(2).mkString("."))

      // Acceptance 4: the second /start wrote no account and left no session.
      assertEquals(rig.uow.sessions.all, Nil, "no leftover sessions")

  test("/help lists every registered command including itself (K9)"):
    ox.supervised:
      val rig = FirstFlowsRig()
      rig.line("/start")
      (1 to 3).foreach(_ => rig.line("1")) // Medication, I agree, Confirm
      rig.line("13")
      rig.line("1")
      val help = rig.line("/help")
      CommandRegistry.all.foreach: spec =>
        assert(help.exists(_.contains(s"/${spec.name}")), s"/help lists /${spec.name}")
      assert(help.exists(_.contains("/help")), "/help lists itself")

  test("ConversationStarted begins the same create flow (DESIGN.md section 4: the vendor's DM-open event)"):
    ox.supervised:
      val rig = FirstFlowsRig()
      val out = rig.push(Inbound.ConversationStarted(None))
      assert(out.exists(_.contains(ConsentCopy.notice.head)), s"the notice card: $out")
      assertEquals(rig.uow.sessions.all.size, 1, "a create session started")

  // BOT_UX BUX-CMD-08: no command of a domain exists before the domain has a consent purpose.
  test("/mood is not a command: the registry and /help do not list it, and typing it records nothing"):
    ox.supervised:
      val rig = FirstFlowsRig()
      rig.line("/start")
      (1 to 3).foreach(_ => rig.line("1")) // Medication, I agree, Confirm
      rig.line("13")
      rig.line("1")
      assert(!CommandRegistry.all.exists(_.name == "mood"), "the registry lists no mood command")
      val help = rig.line("/help")
      assert(!help.exists(_.contains("/mood")), s"help: $help")
      val out = rig.line("/mood 8")
      assert(out.exists(_.contains(dosegram.core.domain.copy.HelpCopy.unknownCommand)), s"got: $out")
      assertEquals(rig.uow.domainEvents.all.map(_.eventType), List(Event.AccountCreatedType), "nothing recorded")

/** The transcript rig: the real mediator, engine, flows and handlers over the in-memory ports and a
  * `FakeAdapter(CapabilityProfiles.Console)`. Drives console-grammar input lines and collects the printed chunks.
  */
private final class FirstFlowsRig(using Ox):
  import FirstFlowsRig.*

  val uow = InMemoryUnitOfWork()
  private val clock = FixedClock(t0)
  private val fake = FakeAdapter(CapabilityProfiles.Console, vendor = "fake")
  private val adapter = SyncAdapter(fake)
  private val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
  private val mediator =
    ChatMediator(uow, adapters, codec, FirstFlows.handler(uow, adapters, codec, clock), clock, CommandRegistry.byName)

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
      s"fake:msg:test:$seq",
      t0,
      actor = actor,
      chat = ChatRef("fake", "dm:user-1"),
      body = body
    )
    val before = sendsOf().size
    mediator.push(event)
    await(sendsOf().size > before, s"'$input' produced a reply")
    sendsOf().drop(before).flatMap(_.message.chunks)

  /** Pushes a raw inbound body (not console-grammar text) and returns the newly printed chunks. */
  def push(body: Inbound): List[String] =
    seq += 1
    val event = InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      s"fake:msg:test:$seq",
      t0,
      actor = actor,
      chat = ChatRef("fake", "dm:user-1"),
      body = body
    )
    val before = sendsOf().size
    mediator.push(event)
    await(sendsOf().size > before, s"$body produced a reply")
    sendsOf().drop(before).flatMap(_.message.chunks)

  def drive(script: List[String]): String =
    script.flatMap(input => s"> $input" +: line(input)).mkString("\n") + "\n"

  private def sendsOf(): List[VendorOp.Send] =
    fake.synchronized(fake.ops.collect { case s: VendorOp.Send => s })

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

private object FirstFlowsRig:
  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  /** The vendor supplies a display name fixture; `users.display_name` must never copy it (K8). */
  val actor: PlatformIdentity = PlatformIdentity("fake", "user-1", Some("VendorNick123"))

  /** The acceptance script: create with a wrong turn, the refused /mood, help, and the refused second create. */
  val Script: List[String] = List(
    "/start",
    "1", // Medication
    "1", // I agree
    "1", // Confirm
    "22", // Other — type your timezone
    "Mars/Olympus_Mons", // invalid zone: the picker is re-shown, no row written
    "13", // UTC+03:00 — Europe/Kyiv
    "1", // Yes
    "/mood 8", // no mood consent yet: not a command (BUX-CMD-08)
    "/help",
    "/start" // already linked: catalogue error, no row
  )

  val ResourcePath = "/goldens/transcripts/first-flows.console.golden"
  val FilePath = "core/test/resources/goldens/transcripts/first-flows.console.golden"

  def expectedGolden: String =
    val stream = Option(getClass.getResourceAsStream(ResourcePath))
      .getOrElse(throw IllegalStateException(s"$ResourcePath is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

  /** Synchronized window over FakeAdapter's unsynchronized recording buffers (same pattern as ChatMediatorSuite). */
  final class SyncAdapter(val inner: FakeAdapter) extends ChatAdapter:
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
    override def registerCommands(specs: List[CommandSpec]): Unit = around(inner.registerCommands(specs))
    override def resolveChat(identity: PlatformIdentity): ChatRef = around(inner.resolveChat(identity))
    override def renderText(text: RichText): List[String] = around(inner.renderText(text))

/** Regenerates the transcript golden: `./mill core.test.runMain dosegram.core.application.firstFlowsGoldenGenerate`. */
@main def firstFlowsGoldenGenerate(): Unit =
  ox.supervised:
    val transcript = FirstFlowsRig().drive(FirstFlowsRig.Script)
    val target = Path.of(FirstFlowsRig.FilePath)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, transcript)
    println(s"wrote $target")
