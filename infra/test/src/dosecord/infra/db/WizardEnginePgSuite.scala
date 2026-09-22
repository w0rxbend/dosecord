package dosecord.infra.db

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.*

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*
import scala.language.implicitConversions

/** M0.12b acceptance on Testcontainers Postgres 18: restart resume with `last_prompt` re-send, the stale `step_seq`
  * tap through the full mediator stack, `updated_at` advancing on every step, the sweeper grace/abort lifecycle, the
  * FormRunner on the text-only console profile, and the direct-token bypass (ROADMAP M0.12b).
  */
class WizardEnginePgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  private val actor = PlatformIdentity("fake", "user-1")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try
        st.execute(
          "TRUNCATE inbound_events, domain_events, auth_audit_log, rendered_messages, outbox_messages, " +
            "conversation_sessions, form_runs, callback_slots, platform_identities, users CASCADE"
        )
      finally st.close()
    }

  // ---------- Example flows (fixtures duplicated from core/test; M1.9/M0.12d plug in real flows) ----------

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  private def outbound(text: String, key: String): OutboundMessage =
    OutboundMessage(body = List(paragraph(text)), dedupeKey = key, correlationId = s"c-$key")

  private val example: Flow = Flow(
    id = "example",
    firstStep = "name",
    steps = Map(
      "name" -> Step(
        id = "name",
        kind = StepKind.Text,
        render = _ => List(paragraph("What should I call it?")),
        accept = {
          case (StepInput.TextEntered(text), _) if text.nonEmpty =>
            Right(StepTransition.Next("color", Map("name" -> text)))
          case _ => Left("Please type a name.")
        }
      ),
      "color" -> Step(
        id = "color",
        kind = StepKind.choices(List("red" -> "Red", "blue" -> "Blue")),
        render = data => List(paragraph(s"Pick a color for ${data("name")}.")),
        accept = {
          case (StepInput.Chosen(key), _) if Set("red", "blue").contains(key) =>
            Right(StepTransition.Next("confirm", Map("color" -> key)))
          case _ => Left("Pick one of the colors.")
        }
      ),
      "confirm" -> Step(
        id = "confirm",
        kind = StepKind.choices(List("create" -> Labels.Create)),
        render = data => List(paragraph(s"${data("name")}, ${data("color")} — create it?")),
        accept = {
          case (StepInput.Chosen("create"), _) => Right(StepTransition.Complete)
          case _                               => Left("Tap Create to finish.")
        }
      )
    ),
    onComplete = (data, _) => Reply(followUps = List(outbound(s"Created ${data("name")} (${data("color")}).", "done")))
  )

  private val formFields: List[Field] = List(
    Field("a", "Field A", FieldType.Text, placeholder = Some("e.g. one")),
    Field("b", "Field B", FieldType.Text, required = false)
  )

  private val formFlow: Flow = Flow(
    id = "formflow",
    firstStep = "details",
    steps = Map(
      "details" -> Step(
        id = "details",
        kind = StepKind.Form("details-form", "Details", formFields),
        render = _ => List(paragraph("Fill in the details.")),
        accept = {
          case (StepInput.FormAnswered(fields), _) =>
            Right(StepTransition.Next("done", Map("a" -> fields("a"), "b" -> fields.getOrElse("b", ""))))
          case _ => Left("The form, please.")
        }
      ),
      "done" -> Step(
        id = "done",
        kind = StepKind.choices(List("finish" -> "Finish")),
        render = data => List(paragraph(s"A=${data("a")} B=${data("b")}")),
        accept = {
          case (StepInput.Chosen("finish"), _) => Right(StepTransition.Complete)
          case _                               => Left("Tap Finish.")
        }
      )
    ),
    onComplete = (data, _) => Reply(followUps = List(outbound(s"Form done ${data("a")}/${data("b")}.", "form-done")))
  )

  private val flows: Map[String, Flow] = List(example, formFlow).map(f => f.id -> f).toMap
  private val flowCommands: Map[String, String] = Map("example" -> "example", "form" -> "formflow")

  // ---------- Harness ----------

  private final class TickingClock(var at: Instant) extends Clock:
    override def now(): Instant = at

  private final class RecordingHandler(reply: InboundEvent => Reply) extends ChatHandler:
    val received = new ConcurrentLinkedQueue[(InboundEvent, Principal)]()
    def calls: List[(InboundEvent, Principal)] = received.asScala.toList
    def handled: Int = received.size()
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
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

  private final class Rig:
    val clock = TickingClock(t0)
    val uow = PgUnitOfWork(dataSource, clock)
    val inner = RecordingHandler(_ => Reply(followUps = List(outbound("inner handled", "inner-1"))))
    val adapter = SyncAdapter(FakeAdapter(CapabilityProfiles.Console))
    val adapters: Map[String, ChatAdapter] = Map("fake" -> adapter)
    val engine = WizardEngine(flows, flowCommands, codec, clock, inner, uow, adapters)
    val sweeper = SessionSweeper(uow, adapters, codec, clock)
    def mediator(using ox.Ox): ChatMediator = ChatMediator(uow, adapters, codec, engine, clock)

  private def event(body: Inbound, vendorEventId: String = UUID.randomUUID().toString): InboundEvent =
    InboundEvent(
      EventId(UUID.randomUUID()),
      "fake",
      vendorEventId,
      t0,
      actor = actor,
      chat = ChatRef("fake", "chat-1"),
      principal = None,
      body = body
    )

  private def command(name: String): InboundEvent = event(Inbound.CommandInvoked(name, Map.empty, s"/$name"))
  private def message(text: String): InboundEvent = event(Inbound.MessageReceived(text, None, truncated = false))
  private def tap(wire: String): InboundEvent =
    val payload = codec.decode(wire).toOption.get
    event(
      Inbound.InteractionSubmitted(
        CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire),
        Nil,
        None
      )
    )

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(20)
      ok = cond
    assert(ok, clue)

  private def sendsOf(adapter: SyncAdapter): List[VendorOp.Send] =
    adapter.inner.synchronized(adapter.inner.ops.collect { case s: VendorOp.Send => s })

  private def choiceWire(adapter: SyncAdapter, containing: String, label: String): String =
    adapter.inner.synchronized:
      adapter.inner.sent
        .collectFirst:
          case (_, rendered) if rendered.chunks.exists(_.contains(containing)) =>
            rendered.choiceMap.collectFirst { case e if e.label == label => e.callback }
        .flatten
        .getOrElse(throw new NoSuchElementException(s"no message containing '$containing' with choice '$label'"))

  private def countSql(query: String): Long = withConnection { conn =>
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      rs.next()
      rs.getLong(1)
    finally st.close()
  }

  private final case class SessionRow(step: String, stepSeq: Int, version: Int, updatedAt: Instant, data: String):
    def answers: Map[String, String] = WizardDocument.dataFromJson(data).filterNot(_._1.startsWith("_"))

  private given RowMapper[SessionRow] = rs =>
    SessionRow(
      rs.getString("step"),
      rs.getInt("step_seq"),
      rs.getInt("version"),
      rs.instant("updated_at"),
      rs.getString("data")
    )

  private def sessionRow(): Option[SessionRow] = withConnection { conn =>
    given Connection = conn
    sql"SELECT step, step_seq, version, updated_at, data FROM conversation_sessions".queryOne[SessionRow]()
  }

  // Acceptance 1: kill mid-flow and restart resumes the same step (persist-then-send + last_prompt re-send).
  test("kill mid-flow and restart: the stored prompt is re-sent and the flow resumes the same step"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("example"))
      await(sendsOf(rig.adapter).size == 1, "first prompt delivered")
      m.push(message("Widget"))
      await(sendsOf(rig.adapter).size == 2, "second prompt delivered")
      assertEquals(sessionRow().map(_.step), Some("color"))

      // Process "restarts": a new engine over the same database re-sends the stored prompts.
      val restarted =
        WizardEngine(flows, flowCommands, codec, rig.clock, rig.inner, rig.uow, rig.adapters)
      val resent = restarted.resumePending()
      assertEquals(resent, 1)
      await(sendsOf(rig.adapter).size == 3, "stored prompt re-sent after restart")
      assert(
        sendsOf(rig.adapter).last.message.chunks.exists(_.contains("Pick a color for Widget.")),
        "the re-sent prompt is the current step's"
      )
      assertEquals(sessionRow().map(_.step), Some("color"), "the session resumes the same step")

      // The flow continues from the re-sent prompt: its tokens survived the restart.
      m.push(tap(choiceWire(rig.adapter, "Pick a color", "Red")))
      await(sendsOf(rig.adapter).size == 4, "the flow advances after the restart")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("create it?")))
      assertEquals(sessionRow().map(_.step), Some("confirm"))

  // Acceptance 2 (full stack): a stale step_seq tap gets a toast and the current prompt.
  test("a stale step_seq tap through the mediator gets the toast and the current prompt"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("example"))
      await(sendsOf(rig.adapter).size == 1, "first prompt")
      m.push(message("Widget"))
      await(sendsOf(rig.adapter).size == 2, "color prompt")
      val staleCancel = choiceWire(rig.adapter, "Pick a color", Labels.Cancel) // minted at step_seq 1
      m.push(tap(choiceWire(rig.adapter, "Pick a color", "Red")))
      await(sendsOf(rig.adapter).size == 3, "confirm prompt")

      m.push(tap(staleCancel))
      await(sendsOf(rig.adapter).size == 5, "stale tap answered with the current prompt and the toast")
      val sends = sendsOf(rig.adapter)
      assert(sends(3).message.chunks.exists(_.contains("create it?")), "the current prompt is re-sent")
      assert(
        sends(4).message.chunks == List(ReminderCopy.staleControlToast),
        s"the catalogue toast: ${sends(4).message.chunks}"
      )
      assertEquals(sessionRow().map(_.step), Some("confirm"), "the session is untouched by the stale tap")

  // Acceptance 3: conversation_sessions.updated_at advances on every step.
  test("updated_at advances on every step"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("example"))
      await(sessionRow().isDefined, "session inserted")
      val atInsert = sessionRow().get.updatedAt

      rig.clock.at = t0.plusSeconds(60)
      m.push(message("Widget"))
      await(sessionRow().exists(_.step == "color"), "step 2 persisted")
      val atStep2 = sessionRow().get.updatedAt
      assert(atStep2.isAfter(atInsert), s"updated_at advances on step 2: $atInsert -> $atStep2")

      rig.clock.at = t0.plusSeconds(120)
      m.push(tap(choiceWire(rig.adapter, "Pick a color", "Red")))
      await(sessionRow().exists(_.step == "confirm"), "step 3 persisted")
      val atStep3 = sessionRow().get.updatedAt
      assert(atStep3.isAfter(atStep2), s"updated_at advances on step 3: $atStep2 -> $atStep3")

  // Acceptance 4: advance 30 min -> "Still there?" prompt; +30 min -> session aborted and slots deleted.
  test("sweeper: 30 min idle prompts 'Still there?'; a further 30 min aborts and deletes the slots"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("example"))
      await(sendsOf(rig.adapter).size == 1, "first prompt")
      m.push(message("Widget"))
      await(sendsOf(rig.adapter).size == 2, "color prompt with its slots")
      val slotsBefore = countSql("SELECT count(*) FROM callback_slots")
      assert(slotsBefore > 0, "the choice prompt minted slots")

      // 31 minutes idle: the grace prompt goes out and the session is marked, not killed.
      rig.clock.at = t0.plusSeconds(31 * 60)
      val grace = rig.sweeper.sweepOnce()
      assertEquals(grace, SessionSweeper.Report(graced = 1, aborted = 0, slotsDeleted = 0))
      await(sendsOf(rig.adapter).size == 3, "grace prompt delivered")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains(WizardCopy.stillThere)))
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 1L, "the session survives the grace prompt")

      // Continue: the current step's prompt is re-rendered and the grace marker clears.
      m.push(tap(choiceWire(rig.adapter, WizardCopy.stillThere, Labels.Continue)))
      await(sendsOf(rig.adapter).size == 4, "the step prompt is re-rendered after Continue")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("Pick a color for Widget.")))
      assertEquals(sessionRow().map(_.step), Some("color"))

      // Idle again through the grace window: the session is aborted and its slots are deleted.
      rig.clock.at = t0.plusSeconds(63 * 60)
      val regrace = rig.sweeper.sweepOnce()
      assertEquals(regrace.graced, 1, "a new idle window graces again")
      rig.clock.at = t0.plusSeconds(94 * 60)
      val abort = rig.sweeper.sweepOnce()
      assertEquals(abort.aborted, 1, "the lapsed grace aborts the session")
      assert(abort.slotsDeleted > 0, "the session's slots were deleted")
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 0L, "session row deleted")
      assertEquals(countSql("SELECT count(*) FROM callback_slots"), 0L, "all callback slots deleted")
      await(
        sendsOf(rig.adapter).exists(_.message.chunks.exists(_.contains(WizardCopy.setupCancelled))),
        "the prompt is finalized 'Setup cancelled — nothing saved'"
      )

  // Acceptance 5 (console profile, full stack): the FormRunner asks sequentially and the flow completes.
  test("FormRunner on the text-only console profile drives the form step to completion"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("form"))
      await(sendsOf(rig.adapter).size == 1, "the form prompt asks question 1")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("question 1 of 2")))
      assertEquals(countSql("SELECT count(*) FROM form_runs"), 1L, "one form run started")

      m.push(message("1"))
      await(sendsOf(rig.adapter).size == 2, "question 2")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("question 2 of 2")))

      m.push(message("2"))
      await(sendsOf(rig.adapter).size == 3, "the flow advanced past the form step")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("A=1 B=2")))
      assertEquals(countSql("SELECT count(*) FROM form_runs"), 0L, "the completed run is deleted")
      assertEquals(sessionRow().map(_.step), Some("done"))
      assertEquals(
        sessionRow().map(_.answers),
        Some(Map("a" -> "1", "b" -> "2")),
        "the same fields the modal path would have saved"
      )

      m.push(tap(choiceWire(rig.adapter, "A=1 B=2", "Finish")))
      await(sendsOf(rig.adapter).size == 4, "flow completed")
      assert(sendsOf(rig.adapter).last.message.chunks.exists(_.contains("Form done 1/2.")))
      assertEquals(countSql("SELECT count(*) FROM conversation_sessions"), 0L)

  // Acceptance 6 (full stack): a direct dose token mid-wizard is handled and the wizard is untouched.
  test("a direct dose token mid-wizard is handled by the inner handler; the session row is untouched"):
    ox.supervised:
      val rig = Rig()
      val m = rig.mediator

      m.push(command("example"))
      await(sendsOf(rig.adapter).size == 1, "first prompt")
      m.push(message("Widget"))
      await(sessionRow().exists(_.step == "color"), "mid-wizard")
      val before = sessionRow().get

      val direct = codec.encode(CallbackMode.Direct, ActionRegistry.byName("dose.taken").get, UUID.randomUUID(), 0)
      m.push(tap(direct.wire))
      await(rig.inner.handled == 1, "the direct token reached the inner handler")
      await(sendsOf(rig.adapter).size == 3, "the inner reply was delivered")

      val after = sessionRow().get
      assertEquals(after, before, "the wizard session is untouched by the direct token")
