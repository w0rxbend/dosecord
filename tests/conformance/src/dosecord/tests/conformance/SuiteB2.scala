package dosecord.tests.conformance

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.core.domain.copy.ReminderCopy
import dosecord.core.ports.*
import ox.Ox

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/** How suite B2 observes one adapter: the instance under test plus a view of the message texts it delivered (vendors
  * observe their own wire: FakeAdapter records sends, the console prints them).
  */
final case class B2Adapter(adapter: ChatAdapter, deliveredTexts: () => List[String])

/** Suite B2 wiring: one profile's adapter, fresh per scenario. */
final case class B2Wiring(
    vendor: String,
    profile: CapabilityProfile,
    newAdapter: () => B2Adapter,
    codec: CallbackCodec
)

/** Suite B2 (ROADMAP M0.13): mediator resolution parameterised by profile — quoted reply, the bare-digit gate, stale
  * `step_seq`, ephemeral downgrade and per-chat ordering — through the real `ChatMediator` and `WizardEngine` on the
  * in-memory ports. Every scenario returns its errors instead of throwing, so one run reports every broken rule.
  */
object SuiteB2:

  final case class Result(name: String, errors: List[String]):
    def passed: Boolean = errors.isEmpty

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")

  final class FixedClock(at: Instant) extends Clock:
    override def now(): Instant = at

  def run(wiring: B2Wiring)(using Ox): List[Result] =
    List(
      "quoted-reply" -> (() => quotedReply(wiring)),
      "bare-digit-gate" -> (() => bareDigitGate(wiring)),
      "stale-step" -> (() => staleStep(wiring)),
      "ephemeral-downgrade" -> (() => ephemeralDowngrade(wiring)),
      "ordering" -> (() => ordering(wiring))
    ).map { (name, scenario) =>
      try scenario().copy(name = name)
      catch
        case scala.util.control.NonFatal(e) =>
          Result(name, List(s"scenario crashed: ${Option(e.getMessage).getOrElse(e.toString)}"))
    }

  // ---------- Harness ----------

  private final class Checks:
    private val failures = ListBuffer.empty[String]
    def check(ok: Boolean, clue: => String): Unit = if !ok then failures += clue
    def result(name: String): Result = Result(name, failures.toList)

  /** One mediator + adapter + in-memory ports; `handler` answers per event (or is the WizardEngine). */
  private final class Harness(wiring: B2Wiring, handler: ChatHandler)(using Ox):
    val uow = InMemoryUnitOfWork()
    val b2 = wiring.newAdapter()
    val clock = FixedClock(t0)
    val mediator = ChatMediator(uow, Map(wiring.vendor -> b2.adapter), wiring.codec, handler, clock)

    def event(body: Inbound, id: String, chatId: String = "dm:owner"): InboundEvent =
      InboundEvent(
        eventId = EventId(UUID.randomUUID()),
        vendor = wiring.vendor,
        vendorEventId = s"${wiring.vendor}:b2:$id",
        receivedAt = t0,
        createdAt = Some(t0),
        actor = PlatformIdentity(wiring.vendor, "owner"),
        chat = ChatRef(wiring.vendor, chatId),
        body = body
      )

    def push(body: Inbound, id: String, chatId: String = "dm:owner"): Unit =
      mediator.push(event(body, id, chatId))

  private final class ScriptedHandler(logic: InboundEvent => Reply) extends ChatHandler:
    val calls = ConcurrentLinkedQueue[InboundEvent]()
    override def handle(event: InboundEvent, principal: Principal, tx: Tx): Reply =
      calls.add(event)
      logic(event)

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 15_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    if !ok then throw AssertionError(clue)

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  private def menuOpen: ActionEntry =
    ActionRegistry.byName("menu.open").get

  private def token(wiring: B2Wiring): String =
    wiring.codec.encode(CallbackMode.Direct, menuOpen, UUID.randomUUID(), 0).wire

  private def prompt(id: String, text: String, tokens: List[String]): OutboundMessage =
    OutboundMessage(
      body = List(paragraph(text)),
      blocks = List(
        Block.Choices(ChoiceSet(id, None, tokens.zipWithIndex.map((t, i) => Choice(s"choice-${i + 1}", t))))
      ),
      dedupeKey = s"b2:$id",
      correlationId = s"b2:$id"
    )

  private def decodeRef(wiring: B2Wiring, callback: String): CallbackRef =
    wiring.codec.decode(callback) match
      case Right(payload) =>
        CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, callback)
      case Left(error) => throw AssertionError(s"choice_map carried an undecodable token: $error")

  // ---------- Scenarios ----------

  /** Quoted-reply numbers resolve by the target's choice_map unconditionally — even once a newer prompt is the latest.
    */
  private def quotedReply(wiring: B2Wiring)(using Ox): Result =
    val checks = Checks()
    val tokensA = List(token(wiring), token(wiring))
    val tokensB = List(token(wiring), token(wiring))
    val handler = ScriptedHandler: event =>
      event.body match
        case Inbound.MessageReceived("menu-a", _, _) => Reply(followUps = List(prompt("csA", "Pick A:", tokensA)))
        case Inbound.MessageReceived("menu-b", _, _) => Reply(followUps = List(prompt("csB", "Pick B:", tokensB)))
        case _                                       => Reply.empty
    val harness = Harness(wiring, handler)
    import harness.*
    push(Inbound.MessageReceived("menu-a", None, truncated = false), "e1")
    await(uow.renderedMessages.all.nonEmpty, "prompt A was recorded")
    val handleA = uow.renderedMessages.all.head._1
    push(Inbound.MessageReceived("menu-b", None, truncated = false), "e2")
    await(uow.renderedMessages.all.size == 2, "prompt B was recorded")
    val handleB = uow.renderedMessages.all(1)._1

    push(Inbound.MessageReceived("2", Some(handleA), truncated = false), "e3")
    await(handler.calls.size >= 3, "the quoted reply was handled")
    val resolved = handler.calls.asScala.find(_.vendorEventId.endsWith(":e3")).map(_.body)
    resolved match
      case Some(Inbound.InteractionSubmitted(ref, _, source)) =>
        checks.check(ref.raw == tokensA(1), s"quoted '2' on message A resolved to ${ref.raw}, expected ${tokensA(1)}")
        checks.check(source.contains(handleA), s"the resolution source is message A, got $source")
      case other => checks.check(false, s"quoted reply did not resolve, handler saw $other")

    // B is now the latest pending prompt; a bare "2" resolves against B while the quoted reply still hits A.
    push(Inbound.MessageReceived("2", None, truncated = false), "e4")
    await(handler.calls.size >= 4, "the bare digit was handled")
    val bare = handler.calls.asScala.find(_.vendorEventId.endsWith(":e4")).map(_.body)
    bare match
      case Some(Inbound.InteractionSubmitted(ref, _, source)) =>
        checks.check(
          ref.raw == tokensB(1),
          s"bare '2' resolved to ${ref.raw}, expected the latest prompt's ${tokensB(1)}"
        )
        checks.check(source.contains(handleB), s"the bare digit resolved against the latest prompt B, got $source")
      case other => checks.check(false, s"bare digit did not resolve against the latest prompt, handler saw $other")
    checks.result("quoted-reply")

  /** The bare-digit gate: bare digits resolve only against the latest pending prompt; once every prompt's controls are
    * removed, the digit passes through as plain text.
    */
  private def bareDigitGate(wiring: B2Wiring)(using Ox): Result =
    val checks = Checks()
    val tokensA = List(token(wiring), token(wiring))
    val tokensB = List(token(wiring), token(wiring))
    val handler = ScriptedHandler: event =>
      event.body match
        case Inbound.MessageReceived("menu-a", _, _) => Reply(followUps = List(prompt("csA", "Pick A:", tokensA)))
        case Inbound.MessageReceived("menu-b", _, _) => Reply(followUps = List(prompt("csB", "Pick B:", tokensB)))
        case _                                       => Reply.empty
    val harness = Harness(wiring, handler)
    import harness.*
    push(Inbound.MessageReceived("menu-a", None, truncated = false), "e1")
    await(uow.renderedMessages.all.nonEmpty, "prompt A was recorded")
    val handleA = uow.renderedMessages.all.head._1
    push(Inbound.MessageReceived("menu-b", None, truncated = false), "e2")
    await(uow.renderedMessages.all.size == 2, "prompt B was recorded")

    // B is the latest pending prompt: a bare "1" resolves against B, never against A.
    push(Inbound.MessageReceived("1", None, truncated = false), "e3")
    await(handler.calls.size >= 3, "the bare digit was handled")
    val bare = handler.calls.asScala.find(_.vendorEventId.endsWith(":e3")).map(_.body)
    checks.check(
      bare.exists { case Inbound.InteractionSubmitted(ref, _, _) => ref.raw == tokensB(0); case _ => false },
      s"bare '1' must resolve against the latest prompt B (${tokensB(0)}), got $bare"
    )

    // The quoted reply resolves against A by target, unconditionally.
    push(Inbound.MessageReceived("1", Some(handleA), truncated = false), "e4")
    await(handler.calls.size >= 4, "the quoted reply was handled")
    val quoted = handler.calls.asScala.find(_.vendorEventId.endsWith(":e4")).map(_.body)
    checks.check(
      quoted.exists { case Inbound.InteractionSubmitted(ref, _, _) => ref.raw == tokensA(0); case _ => false },
      s"quoted '1' on A must resolve to ${tokensA(0)}, got $quoted"
    )

    // Every prompt finalized: a bare digit is not eaten; it reaches the handler as plain text.
    uow.renderedMessages.removeControls(handleA)
    uow.renderedMessages.all.lift(1).foreach((h, _, _) => uow.renderedMessages.removeControls(h))
    push(Inbound.MessageReceived("1", None, truncated = false), "e5")
    await(handler.calls.size >= 5, "the pass-through digit was handled")
    val passThrough = handler.calls.asScala.find(_.vendorEventId.endsWith(":e5")).map(_.body)
    checks.check(
      passThrough.exists { case Inbound.MessageReceived("1", None, _) => true; case _ => false },
      s"a bare digit with no pending prompt must pass through as text, got $passThrough"
    )
    checks.result("bare-digit-gate")

  /** A stale `step_seq` tap gets the toast and the current prompt again (DESIGN.md section 4.6). */
  private def staleStep(wiring: B2Wiring)(using Ox): Result =
    val checks = Checks()
    val flow = Flow(
      id = "b2flow",
      firstStep = "pick",
      steps = Map(
        "pick" -> Step(
          id = "pick",
          kind = StepKind.choices(List("a" -> "Option A", "b" -> "Option B")),
          render = _ => List(paragraph("Choose:")),
          accept = {
            case (StepInput.Chosen("a"), _) => Right(StepTransition.Next("value"))
            case (StepInput.Chosen("b"), _) => Right(StepTransition.Next("value"))
            case _                          => Left("invalid")
          }
        ),
        "value" -> Step(
          id = "value",
          kind = StepKind.Text,
          render = _ => List(paragraph("Enter value:")),
          accept = { case (StepInput.TextEntered(_), _) => Right(StepTransition.Complete); case _ => Left("invalid") }
        )
      ),
      onComplete = (_, _) => Reply.empty
    )
    val uow = InMemoryUnitOfWork()
    val b2 = wiring.newAdapter()
    val inner = ScriptedHandler(_ => Reply.empty)
    val wizard = WizardEngine(
      flows = Map("b2flow" -> flow),
      flowCommands = Map("setup" -> "b2flow"),
      codec = wiring.codec,
      clock = FixedClock(t0),
      inner = inner,
      uow = uow,
      adapters = Map(wiring.vendor -> b2.adapter)
    )
    val mediator = ChatMediator(uow, Map(wiring.vendor -> b2.adapter), wiring.codec, wizard, FixedClock(t0))
    def push(body: Inbound, id: String): Unit =
      mediator.push(
        InboundEvent(
          eventId = EventId(UUID.randomUUID()),
          vendor = wiring.vendor,
          vendorEventId = s"${wiring.vendor}:b2:$id",
          receivedAt = t0,
          createdAt = Some(t0),
          actor = PlatformIdentity(wiring.vendor, "owner"),
          chat = ChatRef(wiring.vendor, "dm:owner"),
          body = body
        )
      )

    push(Inbound.CommandInvoked("setup", Map.empty, "/setup"), "e1")
    await(uow.renderedMessages.all.nonEmpty, "the wizard's first prompt was recorded")
    val handleP1 = uow.renderedMessages.all.head._1
    val choiceA = uow.renderedMessages
      .choiceMapFor(handleP1)
      .flatMap(_.choiceMap.find(_.label == "Option A"))
      .getOrElse(throw AssertionError("the first prompt carries Option A"))
    val ref = decodeRef(wiring, choiceA.callback)

    // Tap Option A: the session advances to the text step (step_seq 1).
    push(Inbound.InteractionSubmitted(ref, Nil, Some(handleP1)), "e2")
    await(uow.renderedMessages.all.size == 2, "the text-step prompt was recorded")

    // Tap the same control again: stale step_seq -> toast + the current prompt.
    val deliveredBefore = b2.deliveredTexts().size
    push(Inbound.InteractionSubmitted(ref, Nil, Some(handleP1)), "e3")
    await(b2.deliveredTexts().size >= deliveredBefore + 2, "the stale tap produced the current prompt and the toast")
    val delivered = b2.deliveredTexts()
    checks.check(
      delivered.exists(_.contains("Enter value:")),
      s"the stale tap re-sends the current prompt, delivered: $delivered"
    )
    checks.check(
      delivered.exists(_.contains(ReminderCopy.staleControlToast)),
      s"the stale tap toasts '${ReminderCopy.staleControlToast}', delivered: $delivered"
    )
    checks.result("stale-step")

  /** Ephemeral downgrade on the ladder (DESIGN.md section 4.3): with no live interaction the native rung is
    * unreachable, so a text-only profile degrades ephemeral to the "not kept" notice.
    */
  private def ephemeralDowngrade(wiring: B2Wiring)(using Ox): Result =
    val checks = Checks()
    val handler = ScriptedHandler: event =>
      event.body match
        case Inbound.MessageReceived("ephemeral", _, _) =>
          Reply(followUps =
            List(
              OutboundMessage(
                body = List(paragraph("Heads up")),
                visibility = Visibility.Ephemeral,
                dedupeKey = "b2:eph",
                correlationId = "b2:eph"
              )
            )
          )
        case _ => Reply.empty
    val harness = Harness(wiring, handler)
    import harness.*
    push(Inbound.MessageReceived("ephemeral", None, truncated = false), "e1")
    await(b2.deliveredTexts().nonEmpty, "the ephemeral reply was delivered")
    val expectedRung =
      val deletable = wiring.profile.deleteOwn != DeleteCapability.NoDelete
      val windowOk = wiring.profile.deleteOwn match
        case DeleteCapability.Window(limit) => limit.compareTo(Duration.ofSeconds(60)) >= 0
        case _                              => true
      if deletable && windowOk then "autoDelete" else "notice"
    val delivered = b2.deliveredTexts().mkString("\n")
    checks.check(
      delivered.contains("Heads up"),
      s"the reply body was delivered, got: $delivered"
    )
    if expectedRung == "notice" then
      checks.check(
        delivered.contains("This message will not be kept."),
        s"ephemeral on a text-only profile downgrades to the notice, got: $delivered"
      )
    else
      checks.check(
        !delivered.contains("This message will not be kept."),
        s"ephemeral on a delete-capable profile must not degrade to the notice, got: $delivered"
      )
    checks.result(s"ephemeral-downgrade($expectedRung)")

  /** Per-chat ordering: interleaved events on two chats are handled in per-chat FIFO order. */
  private def ordering(wiring: B2Wiring)(using Ox): Result =
    val checks = Checks()
    val handler = ScriptedHandler: event =>
      // The first chat-a event stalls; without the keyed executor chat-a's second event would overtake it.
      if event.chat.chatId == "dm:a" && event.body == Inbound.MessageReceived("1", None, truncated = false) then
        Thread.sleep(200)
      Reply.empty
    val harness = Harness(wiring, handler)
    import harness.*
    push(Inbound.MessageReceived("1", None, truncated = false), "a1", "dm:a")
    push(Inbound.MessageReceived("x", None, truncated = false), "b1", "dm:b")
    push(Inbound.MessageReceived("2", None, truncated = false), "a2", "dm:a")
    push(Inbound.MessageReceived("y", None, truncated = false), "b2", "dm:b")
    await(handler.calls.size == 4, "every event was handled")
    val perChat = handler.calls.asScala.toList.groupBy(_.chat.chatId).map { (chat, events) =>
      chat -> events.map(_.body match
        case Inbound.MessageReceived(text, _, _) => text
        case other                               => other.productPrefix)
    }
    checks.check(
      perChat.get("dm:a").contains(List("1", "2")),
      s"chat a handled out of order: ${perChat.get("dm:a")}"
    )
    checks.check(
      perChat.get("dm:b").contains(List("x", "y")),
      s"chat b handled out of order: ${perChat.get("dm:b")}"
    )
    checks.result("ordering")
