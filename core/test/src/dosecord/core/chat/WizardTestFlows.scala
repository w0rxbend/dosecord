package dosecord.core.chat

import dosecord.contracts.*
import dosecord.core.domain.copy.Labels
import dosecord.core.ports.*

import java.time.Instant
import java.util.UUID

/** The small example flows that prove the engine (ROADMAP M0.12b: "a small example/test flow is enough"); M1.9's
  * add-medication wizard and M0.12d's timezone picker plug into the same model later.
  */
object WizardTestFlows:

  def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))

  def outbound(text: String, key: String): OutboundMessage =
    OutboundMessage(body = List(paragraph(text)), dedupeKey = key, correlationId = s"c-$key")

  /** name (text) -> color (choices) -> confirm (choices, Complete). */
  val example: Flow = Flow(
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
        kind = StepKind.Choices(List("red" -> "Red", "blue" -> "Blue")),
        render = data => List(paragraph(s"Pick a color for ${data("name")}.")),
        accept = {
          case (StepInput.Chosen(key), _) if Set("red", "blue").contains(key) =>
            Right(StepTransition.Next("confirm", Map("color" -> key)))
          case _ => Left("Pick one of the colors.")
        }
      ),
      "confirm" -> Step(
        id = "confirm",
        kind = StepKind.Choices(List("create" -> Labels.Create)),
        render = data => List(paragraph(s"${data("name")}, ${data("color")} — create it?")),
        accept = {
          case (StepInput.Chosen("create"), _) => Right(StepTransition.Complete)
          case _                               => Left("Tap Create to finish.")
        }
      )
    ),
    onComplete = data => Reply(followUps = List(outbound(s"Created ${data("name")} (${data("color")}).", "done")))
  )

  val formFields: List[Field] = List(
    Field("a", "Field A", FieldType.Text, placeholder = Some("e.g. one")),
    Field("b", "Field B", FieldType.Text, required = false)
  )

  /** details (form, two fields) -> done (choices, Complete). */
  val formFlow: Flow = Flow(
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
        kind = StepKind.Choices(List("finish" -> "Finish")),
        render = data => List(paragraph(s"A=${data("a")} B=${data("b")}")),
        accept = {
          case (StepInput.Chosen("finish"), _) => Right(StepTransition.Complete)
          case _                               => Left("Tap Finish.")
        }
      )
    ),
    onComplete = data => Reply(followUps = List(outbound(s"Form done ${data("a")}/${data("b")}.", "form-done")))
  )

  val flows: Map[String, Flow] = List(example, formFlow).map(f => f.id -> f).toMap
  val flowCommands: Map[String, String] = Map("example" -> "example", "form" -> "formflow")

/** Synchronous engine harness over the in-memory fakes: `handle` runs the engine inside one fake transaction, as the
  * mediator's per-event transaction would.
  */
final class WizardHarness:
  import WizardTestFlows.*

  val t0: Instant = Instant.parse("2026-09-21T00:00:00Z")
  val codec = CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))
  val uow = MediatorFakes.InMemoryUnitOfWork()
  val clock = MediatorFakes.FixedClock(t0)
  val inner = MediatorFakes.RecordingHandler(_ => Reply(followUps = List(outbound("inner reply", "inner-1"))))
  val adapter = FakeAdapter(CapabilityProfiles.Console)
  val engine = WizardEngine(
    flows,
    flowCommands,
    codec,
    clock,
    inner,
    uow,
    Map(adapter.vendor -> adapter)
  )

  val actor = PlatformIdentity(adapter.vendor, "user-1")
  val principal: Principal = uow.identities.resolve(actor, t0)
  val chat: ChatRef = ChatRef(adapter.vendor, "chat-1")

  def event(body: Inbound, id: String = UUID.randomUUID().toString): InboundEvent =
    InboundEvent(
      EventId(UUID.randomUUID()),
      adapter.vendor,
      id,
      clock.now(),
      actor = actor,
      chat = chat,
      principal = Some(principal),
      body = body
    )

  def handle(e: InboundEvent): Reply = uow.transaction(tx => engine.handle(e, principal, tx))

  def command(name: String): Reply = handle(event(Inbound.CommandInvoked(name, Map.empty, s"/$name")))

  def message(text: String): Reply = handle(event(Inbound.MessageReceived(text, None, truncated = false)))

  def tap(wire: String, source: Option[MessageHandle] = None): Reply =
    handle(event(Inbound.InteractionSubmitted(refOf(wire), Nil, source)))

  def submitForm(formId: String, submitWire: String, fields: Map[String, String]): Reply =
    handle(event(Inbound.FormSubmitted(formId, refOf(submitWire), fields)))

  def refOf(wire: String): CallbackRef =
    val payload = codec.decode(wire).toOption.get
    CallbackRef(payload.action.id, payload.subject, payload.value, payload.mode == CallbackMode.Slot, wire)

  /** The wire token of the choice with this label anywhere in the reply. */
  def choiceWire(reply: Reply, label: String): String =
    (reply.replace.toList ++ reply.followUps)
      .flatMap(_.blocks)
      .collect { case Block.Choices(cs) => cs.choices }
      .flatten
      .collectFirst { case c if c.label == label => c.callback }
      .getOrElse(throw new NoSuchElementException(s"no choice labelled '$label'"))

  def formBlock(reply: Reply): Form =
    (reply.replace.toList ++ reply.followUps)
      .flatMap(_.blocks)
      .collectFirst { case Block.FormBlock(f) => f }
      .getOrElse(throw new NoSuchElementException("no form block"))

  def bodyText(reply: Reply): String =
    (reply.replace.toList ++ reply.followUps)
      .flatMap(_.body)
      .collect { case Node.Paragraph(inlines) =>
        inlines.collect { case Inline.Text(s) => s }.mkString
      }
      .mkString("\n")

  def session: ConversationSession =
    uow.sessions.all.headOption.getOrElse(throw new NoSuchElementException("no session row"))
