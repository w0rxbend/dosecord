package dosecord.tests.conformance

import dosecord.contracts.*

import java.time.Duration
import scala.collection.mutable.ListBuffer

/** The outcome of one scenario run: passed, skipped with the unsatisfied requirements, or failed with the collected
  * errors.
  */
enum ScenarioResult:
  case Passed(name: String)
  case Skipped(name: String, reason: String)
  case Failed(name: String, errors: List[String])

/** The suite A JSON scenario runner (ROADMAP M0.13): executes every scenario on the classpath against the wiring's
  * adapter + fake vendor server. A scenario never throws: failures are collected so one run reports every broken
  * scenario, and a deliberately dishonest profile fails capability honesty instead of crashing the run.
  */
object SuiteARunner:

  def run(wiring: AdapterWiring, scenarios: List[Scenario] = Scenario.loadAll()): List[ScenarioResult] =
    scenarios.map(runOne(wiring, _))

  private final class RunState:
    var lastError = Option.empty[ChatError]
    var renderResult = Option.empty[List[String]]
    val errors = ListBuffer.empty[String]

  private def runOne(wiring: AdapterWiring, scenario: Scenario): ScenarioResult =
    val missing = Scenario.unsatisfied(scenario, wiring)
    if missing.nonEmpty then ScenarioResult.Skipped(scenario.name, s"not applicable: ${missing.mkString(", ")}")
    else
      val state = RunState()
      val aut = wiring.start(scenario.name)
      try
        scenario.steps.foreach: step =>
          state.lastError = None
          try execute(step, aut, wiring, scenario, state)
          catch case e: ChatError => state.lastError = Some(e)
        scenario.expect.foreach: expectation =>
          try evaluate(expectation, aut, wiring, state)
          catch
            case e: Scenario.ScenarioFormatException => state.errors += e.getMessage
            case e: Exception => state.errors += s"${expectation.productPrefix}: ${Option(e.getMessage).getOrElse(e)}"
      catch
        case e: ChatError => state.errors += s"uncaught adapter error: $e"
        case e: Exception => state.errors += s"scenario crashed: ${Option(e.getMessage).getOrElse(e.toString)}"
      finally
        try aut.adapter.stop()
        catch case _: Exception => ()
      if state.errors.isEmpty then ScenarioResult.Passed(scenario.name)
      else ScenarioResult.Failed(scenario.name, state.errors.toList)

  // ---------- Steps ----------

  private def execute(
      step: Step,
      aut: AdapterUnderTest,
      wiring: AdapterWiring,
      scenario: Scenario,
      state: RunState
  ): Unit =
    step match
      case Step.Start(resumeFrom) =>
        aut.server.onStart(resumeFrom)
        aut.adapter.start(aut.sink, resumeFrom)
      case Step.Stop                      => aut.adapter.stop()
      case Step.Deliver(event)            => aut.server.deliver(event)
      case Step.Send(text, sendKey, chat) =>
        aut.adapter.send(chatRef(wiring, chat), RenderedMessage(chunks = List(text)), sendKey)
        ()
      case Step.SendControls(text, controls, sendKey, chat) =>
        aut.adapter.send(
          chatRef(wiring, chat),
          RenderedMessage(chunks = List(text), controls = List(controlsOf(controls))),
          sendKey
        )
        ()
      case Step.Edit(messageId, text, chat) =>
        aut.adapter.edit(handleOf(wiring, aut, messageId, chat), RenderedMessage(chunks = List(text)))
        ()
      case Step.Delete(messageId, chat) =>
        aut.adapter.delete(handleOf(wiring, aut, messageId, chat))
      case Step.React(messageId, emoji, on, chat) =>
        aut.adapter.react(handleOf(wiring, aut, messageId, chat), emoji, on, txnKey = "conformance:react")
      case Step.RegisterCommands(names) =>
        aut.adapter.registerCommands(names.map(n => CommandSpec(n, s"conformance command $n")))
      case Step.InjectFault(fault)    => aut.server.failNext(fault)
      case Step.AdvanceClock(seconds) => aut.server.advanceClock(Duration.ofSeconds(seconds))
      case Step.RenderText            =>
        state.renderResult = Some(
          aut.adapter.renderText(
            scenario.richText.getOrElse(
              throw Scenario.ScenarioFormatException(scenario.name, "renderText step requires a richText field")
            )
          )
        )
      case Step.ExerciseClaimedCapabilities =>
        state.errors ++= exerciseClaimedCapabilities(aut, wiring)
      case Step.OpenForm =>
        val interaction = aut.sink.all.lastOption
          .flatMap(_.interaction)
          .getOrElse(
            throw Scenario.ScenarioFormatException(
              scenario.name,
              "openForm step requires a delivered callback with a live interaction"
            )
          )
        interaction.openForm(
          scenario.form.getOrElse(
            throw Scenario.ScenarioFormatException(scenario.name, "openForm step requires a form field")
          )
        )

  private def chatRef(wiring: AdapterWiring, chat: Option[String]): ChatRef =
    chat.map(id => ChatRef(wiring.vendor, id)).getOrElse(wiring.defaultChat)

  /** `$last` resolves to the most recent sent observation's vendor message id. */
  private def handleOf(
      wiring: AdapterWiring,
      aut: AdapterUnderTest,
      messageId: String,
      chat: Option[String]
  ): MessageHandle =
    val resolved =
      if messageId == "$last" then
        aut.server.sent.lastOption
          .flatMap(_.messageId)
          .getOrElse(throw Scenario.ScenarioFormatException("scenario", "$last used before any send"))
      else messageId
    MessageHandle(wiring.vendor, chatRef(wiring, chat).chatId, resolved)

  private def controlsOf(kind: String): RenderedControls =
    val choice = RenderedChoice(index = 1, label = "One", callback = "dc:conformance")
    kind match
      case "buttons" => RenderedControls.Buttons(List(List(choice)))
      case "select"  =>
        RenderedControls.SelectMenu(id = "conformance", choices = List(choice), minSelect = 1, maxSelect = 1)
      case other => throw Scenario.ScenarioFormatException("scenario", s"unknown controls kind '$other'")

  /** Capability honesty: every capability the profile claims must work when exercised (ROADMAP M0.13 acceptance: a
    * profile claiming `buttons` but rejecting button sends fails here). Ops the profile does not claim are never called
    * by the core, so they are not probed.
    */
  private def exerciseClaimedCapabilities(aut: AdapterUnderTest, wiring: AdapterWiring): List[String] =
    val failures = ListBuffer.empty[String]
    val profile = wiring.profile
    val chat = wiring.defaultChat
    def attempt(capability: String)(op: => Unit): Unit =
      try op
      catch case e: ChatError => failures += s"profile claims '$capability' but the adapter failed it: $e"

    val probe = aut.adapter.send(chat, RenderedMessage(chunks = List("conformance probe")), "conformance:probe")
    if profile.buttons then
      attempt("buttons"):
        aut.adapter.send(
          chat,
          RenderedMessage(chunks = List("Pick"), controls = List(controlsOf("buttons"))),
          "conformance:buttons"
        )
        ()
    if profile.select then
      attempt("select"):
        aut.adapter.send(
          chat,
          RenderedMessage(chunks = List("Pick"), controls = List(controlsOf("select"))),
          "conformance:select"
        )
        ()
    if profile.ephemeral then
      attempt("ephemeral"):
        aut.adapter.send(chat, RenderedMessage(chunks = List("ghost"), ephemeral = true), "conformance:ephemeral")
        ()
    if profile.silentDelivery then
      attempt("silentDelivery"):
        aut.adapter.send(chat, RenderedMessage(chunks = List("quiet"), silent = true), "conformance:silent")
        ()
    if profile.editOwn != EditCapability.NoEdit then
      attempt("editOwn"):
        aut.adapter.edit(probe, RenderedMessage(chunks = List("edited")))
        ()
    if profile.deleteOwn != DeleteCapability.NoDelete then
      attempt("deleteOwn"):
        aut.adapter.delete(probe)
    if profile.botReactions then
      attempt("botReactions"):
        aut.adapter.react(probe, "1️⃣", on = true, txnKey = "conformance:react")
    if profile.nativeCommands then
      attempt("nativeCommands"):
        aut.adapter.registerCommands(List(CommandSpec("conformance", "capability probe")))
    // Interaction-backed capabilities need a live vendor interaction: deliver a callback and use its handle.
    if profile.transientAck || profile.modal then
      aut.server.deliver(
        WireEvent.Callback("conformance-cb", wiring.defaultChat.chatId, "dc:conformance", probe.messageId)
      )
      aut.sink.all.lastOption.flatMap(_.interaction) match
        case None =>
          failures += s"profile claims '${
              if profile.transientAck then "transientAck" else "modal"
            }' but a callback produced no live interaction"
        case Some(interaction) =>
          if profile.transientAck then
            attempt("transientAck"):
              if interaction.acked then () else interaction.answer(None)
          if profile.modal then
            attempt("modal"):
              interaction.openForm(
                RenderedForm(
                  "conformance",
                  "Conformance",
                  List(RenderedField("k", "K", FieldType.Text, required = true, None, Nil)),
                  "dc:conformance"
                )
              )
    failures.toList

  // ---------- Expectations ----------

  private def evaluate(expectation: Expect, aut: AdapterUnderTest, wiring: AdapterWiring, state: RunState): Unit =
    expectation match
      case Expect.InboundCount(count) =>
        check(state, s"expected $count inbound events, got ${aut.sink.all.size}", aut.sink.all.size == count)
      case Expect.Inbound(wireId, kind, text, name, replyToMessageId, chatId, cursorGreaterThan, callback, formId) =>
        val events = aut.server.deliveries.collect { case (wire, event) if wire.wireKey == wireId => event }
        check(state, s"no inbound event delivered for wireId $wireId", events.nonEmpty)
        events.headOption.foreach: event =>
          check(state, s"$wireId: expected $kind, got ${event.body.productPrefix}", bodyKind(event.body) == kind)
          text.foreach(t => check(state, s"$wireId: text mismatch: ${bodyText(event.body)}", bodyText(event.body) == t))
          name.foreach(n => check(state, s"$wireId: name mismatch", bodyName(event.body).contains(n)))
          replyToMessageId.foreach(id => check(state, s"$wireId: replyTo mismatch", replyToOf(event.body).contains(id)))
          chatId.foreach(id => check(state, s"$wireId: chat mismatch: ${event.chat.chatId}", event.chat.chatId == id))
          cursorGreaterThan.foreach(min =>
            val cursor = event.cursor.flatMap(_.toLongOption)
            check(state, s"$wireId: cursor ${event.cursor} is not greater than $min", cursor.exists(_ > min))
          )
          callback.foreach(c =>
            check(state, s"$wireId: callback mismatch: ${bodyCallback(event.body)}", bodyCallback(event.body).contains(c))
          )
          formId.foreach(f =>
            check(state, s"$wireId: formId mismatch: ${bodyFormId(event.body)}", bodyFormId(event.body).contains(f))
          )
      case Expect.CursorMonotonic =>
        val cursors = aut.sink.all.flatMap(_.cursor).flatMap(_.toLongOption)
        check(state, s"cursors not monotonic: $cursors", cursors == cursors.sorted)
      case Expect.SameVendorEventId(wireId) =>
        val ids = aut.server.deliveries.collect {
          case (wire, event) if wire.wireKey == wireId => event.vendorEventId
        }.distinct
        check(state, s"deliveries of $wireId produced different vendor_event_ids: $ids", ids.size == 1)
      case Expect.VendorEventIdsDistinct(wireIds) =>
        val ids = wireIds.flatMap(w =>
          aut.server.deliveries.collectFirst { case (wire, event) if wire.wireKey == w => event.vendorEventId }
        )
        check(state, s"wire ids share vendor_event_ids: $ids", ids.distinct.size == ids.size)
      case Expect.Sent(messageId, text) =>
        val resolvedId = messageId.map {
          case "$last" => aut.server.sent.lastOption.flatMap(_.messageId).getOrElse("<none>")
          case other   => other
        }
        val found = aut.server.sent.exists: observation =>
          resolvedId.forall(_ == observation.messageId.getOrElse("<none>")) &&
            text.forall(_ == observation.text)
        check(state, s"no sent message matching (messageId=$resolvedId, text=$text): ${aut.server.sent}", found)
      case Expect.SentCount(count) =>
        check(state, s"expected $count sent messages, got ${aut.server.sent.size}", aut.server.sent.size == count)
      case Expect.Error(kind, op, retryAfterSeconds, channelFatal) =>
        state.lastError match
          case None        => state.errors += s"expected $kind but the step succeeded"
          case Some(error) =>
            check(state, s"expected $kind, got $error", errorKind(error) == kind)
            op.foreach(o => check(state, s"$kind: op mismatch: $error", eOp(error).contains(o)))
            retryAfterSeconds.foreach(seconds =>
              check(
                state,
                s"$kind: retryAfter mismatch: $error",
                error match
                  case ChatError.RateLimited(retryAfter) => retryAfter.getSeconds == seconds
                  case _                                 => false
              )
            )
            channelFatal.foreach(fatal =>
              check(state, s"$kind: channelFatal mismatch: $error", error.channelFatal == fatal)
            )
      case Expect.NoError =>
        state.lastError.foreach(e => state.errors += s"expected success, got $e")
      case Expect.RenderTextGolden(goldens) =>
        val key = markupKey(wiring.profile.markup)
        goldens.get(key) match
          case None         => state.errors += s"no golden for markup '$key' (have: ${goldens.keySet.mkString(", ")})"
          case Some(golden) =>
            check(
              state,
              s"renderText golden mismatch for '$key': ${state.renderResult}",
              state.renderResult.contains(golden)
            )
      case Expect.RenderTextRespectsMaxText =>
        state.renderResult match
          case None         => state.errors += "renderTextRespectsMaxText before any renderText step"
          case Some(chunks) =>
            val max = wiring.profile.maxText
            check(state, "renderText produced empty chunks", chunks.nonEmpty && chunks.forall(_.nonEmpty))
            check(state, s"chunks exceed maxText=$max: ${chunks.map(_.length)}", chunks.forall(_.length <= max))
      case Expect.LogClean(canaries) =>
        val leaked = canaries.filter(canary => aut.server.logs.exists(_.contains(canary)))
        check(state, s"logs leaked canaries $leaked", leaked.isEmpty)
      case Expect.Acked(withinSeconds) =>
        val deadline = withinSeconds.getOrElse(wiring.profile.ackDeadline.map(_.getSeconds).getOrElse(0L))
        val first = aut.server.acks.headOption
        check(state, s"no ack observed before the deadline (${deadline}s)", first.isDefined)
      case Expect.ModalPayloadContains(values) =>
        val payload = aut.server.modalPayloads.headOption
        check(state, "no raw modal payload observed", payload.isDefined)
        payload.foreach: raw =>
          values.foreach(v => check(state, s"modal payload missing '$v': $raw", raw.contains(v)))

  private def check(state: RunState, clue: => String, ok: Boolean): Unit =
    if !ok then state.errors += clue

  private def bodyKind(body: Inbound): String = body.productPrefix

  private def bodyText(body: Inbound): String = body match
    case Inbound.MessageReceived(text, _, _) => text
    case Inbound.CommandInvoked(_, _, raw)   => raw
    case _                                   => ""

  private def bodyName(body: Inbound): Option[String] = body match
    case Inbound.CommandInvoked(name, _, _) => Some(name)
    case _                                  => None

  private def bodyCallback(body: Inbound): Option[String] = body match
    case Inbound.InteractionSubmitted(ref, _, _) => Some(ref.raw)
    case Inbound.FormSubmitted(_, ref, _)        => Some(ref.raw)
    case _                                       => None

  private def bodyFormId(body: Inbound): Option[String] = body match
    case Inbound.FormSubmitted(formId, _, _) => Some(formId)
    case _                                   => None

  private def replyToOf(body: Inbound): Option[String] = body match
    case Inbound.MessageReceived(_, replyTo, _) => replyTo.map(_.messageId)
    case _                                      => None

  private def errorKind(error: ChatError): String = error match
    case _: ChatError.Retryable   => "retryable"
    case _: ChatError.RateLimited => "rateLimited"
    case _: ChatError.Unreachable => "unreachable"
    case _: ChatError.TooOld      => "tooOld"
    case _: ChatError.Unsupported => "unsupported"
    case _: ChatError.Permanent   => "permanent"

  private def eOp(error: ChatError): Option[String] = error match
    case ChatError.Unsupported(op) => Some(op)
    case _                         => None

  private def markupKey(markup: Markup): String = markup match
    case Markup.Plain        => "plain"
    case Markup.DiscordMd    => "discordMd"
    case Markup.TelegramHtml => "telegramHtml"
    case Markup.ZulipMd      => "zulipMd"
    case Markup.MatrixHtml   => "matrixHtml"
