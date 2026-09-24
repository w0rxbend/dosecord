package dosecord.tests.conformance

import dosecord.contracts.*

import java.nio.file.Paths

/** The parsed form of one suite A JSON scenario (see `resources/conformance/suite-a/`). The loader is deliberately
  * explicit ujson pattern matching: scenario files are the cross-vendor contract, so a malformed file must fail with a
  * precise location, not a macro-derived error.
  */
final case class Scenario(
    name: String,
    title: String,
    requires: List[String],
    steps: List[Step],
    expect: List[Expect],
    richText: Option[RichText],
    form: Option[RenderedForm]
)

enum Step:
  case Start(resumeFrom: Option[String])
  case Stop
  case Deliver(event: WireEvent)
  case Send(text: String, sendKey: String, chat: Option[String])
  case SendControls(text: String, controls: String, sendKey: String, chat: Option[String])
  case Edit(messageId: String, text: String, chat: Option[String])
  case Delete(messageId: String, chat: Option[String])
  case React(messageId: String, emoji: String, on: Boolean, chat: Option[String])
  case RegisterCommands(names: List[String])
  case InjectFault(fault: VendorFault)
  case AdvanceClock(seconds: Long)
  case RenderText
  case ExerciseClaimedCapabilities
  case OpenForm

enum Expect:
  case InboundCount(count: Int)
  case Inbound(
      wireId: String,
      kind: String,
      text: Option[String],
      name: Option[String],
      replyToMessageId: Option[String],
      chatId: Option[String],
      cursorGreaterThan: Option[Long],
      callback: Option[String],
      formId: Option[String]
  )
  case CursorMonotonic
  case SameVendorEventId(wireId: String)
  case VendorEventIdsDistinct(wireIds: List[String])
  case Sent(messageId: Option[String], text: Option[String])
  case SentCount(count: Int)
  case Error(kind: String, op: Option[String], retryAfterSeconds: Option[Long], channelFatal: Option[Boolean])
  case NoError
  case RenderTextGolden(goldens: Map[String, List[String]])
  case RenderTextRespectsMaxText
  case LogClean(canaries: List[String])
  case Acked(withinSeconds: Option[Long])
  case ModalPayloadContains(values: List[String])

object Scenario:

  /** Every scenario file on the classpath under `base`, sorted by file name. */
  def loadAll(base: String = "/conformance/suite-a"): List[Scenario] =
    val url = getClass.getResource(base)
    require(url != null, s"scenario directory $base is not on the classpath")
    val dir = Paths.get(url.toURI).toFile
    val files =
      Option(dir.listFiles()).getOrElse(Array.empty[java.io.File]).filter(_.getName.endsWith(".json")).sortBy(_.getName)
    require(files.nonEmpty, s"no scenario files under $base")
    files.toList.map(f => parse(ujson.read(f), f.getName))

  def parse(json: ujson.Value, source: String): Scenario =
    try
      Scenario(
        name = str(json, "name"),
        title = str(json, "title"),
        requires = strings(json, "requires"),
        steps = json("steps").arr.toList.map(step(_, source)),
        expect = json("expect").arr.toList.map(expect(_, source)),
        richText = json.obj.get("richText").map(richText(_, source)),
        form = json.obj.get("form").map(form(_))
      )
    catch
      case e: ScenarioFormatException => throw e
      case e: Exception               => throw ScenarioFormatException(source, e.getMessage)

  final case class ScenarioFormatException(source: String, detail: String)
      extends RuntimeException(s"$source: ${Option(detail).getOrElse("malformed scenario")}")

  private def str(json: ujson.Value, key: String): String =
    json.obj.get(key).map(_.str).getOrElse(throw new NoSuchElementException(s"missing '$key'"))

  private def strings(json: ujson.Value, key: String): List[String] =
    json.obj.get(key).map(_.arr.toList.map(_.str)).getOrElse(Nil)

  private def step(json: ujson.Value, source: String): Step =
    json("do").str match
      case "start"        => Step.Start(json.obj.get("resumeFrom").map(_.str))
      case "stop"         => Step.Stop
      case "deliver"      => Step.Deliver(wireEvent(json("event"), source))
      case "send"         => Step.Send(str(json, "text"), str(json, "sendKey"), json.obj.get("chat").map(_.str))
      case "sendControls" =>
        Step.SendControls(
          str(json, "text"),
          str(json, "controls"),
          str(json, "sendKey"),
          json.obj.get("chat").map(_.str)
        )
      case "edit"   => Step.Edit(str(json, "messageId"), str(json, "text"), json.obj.get("chat").map(_.str))
      case "delete" => Step.Delete(str(json, "messageId"), json.obj.get("chat").map(_.str))
      case "react"  =>
        Step.React(
          str(json, "messageId"),
          str(json, "emoji"),
          json.obj.get("on").forall(_.bool),
          json.obj.get("chat").map(_.str)
        )
      case "registerCommands"            => Step.RegisterCommands(strings(json, "names"))
      case "injectFault"                 => Step.InjectFault(fault(json, source))
      case "advanceClock"                => Step.AdvanceClock(json("seconds").num.toLong)
      case "renderText"                  => Step.RenderText
      case "exerciseClaimedCapabilities" => Step.ExerciseClaimedCapabilities
      case "openForm"                    => Step.OpenForm
      case other                         => throw ScenarioFormatException(source, s"unknown step '$other'")

  private def expect(json: ujson.Value, source: String): Expect =
    json("expect").str match
      case "inboundCount" => Expect.InboundCount(json("count").num.toInt)
      case "inbound"      =>
        Expect.Inbound(
          wireId = str(json, "wireId"),
          kind = str(json, "kind"),
          text = json.obj.get("text").map(_.str),
          name = json.obj.get("name").map(_.str),
          replyToMessageId = json.obj.get("replyToMessageId").map(_.str),
          chatId = json.obj.get("chatId").map(_.str),
          cursorGreaterThan = json.obj.get("cursorGreaterThan").map(_.num.toLong),
          callback = json.obj.get("callback").map(_.str),
          formId = json.obj.get("formId").map(_.str)
        )
      case "cursorMonotonic"        => Expect.CursorMonotonic
      case "sameVendorEventId"      => Expect.SameVendorEventId(str(json, "wireId"))
      case "vendorEventIdsDistinct" => Expect.VendorEventIdsDistinct(strings(json, "wireIds"))
      case "sent"                   =>
        Expect.Sent(json.obj.get("messageId").map(_.str), json.obj.get("text").map(_.str))
      case "sentCount" => Expect.SentCount(json("count").num.toInt)
      case "error"     =>
        Expect.Error(
          str(json, "kind"),
          json.obj.get("op").map(_.str),
          json.obj.get("retryAfterSeconds").map(_.num.toLong),
          json.obj.get("channelFatal").map(_.bool)
        )
      case "noError"          => Expect.NoError
      case "renderTextGolden" =>
        Expect.RenderTextGolden(
          json("goldens").obj.map { (k, v) => k -> v.arr.toList.map(_.str) }.toMap
        )
      case "renderTextRespectsMaxText" => Expect.RenderTextRespectsMaxText
      case "logClean"                  => Expect.LogClean(strings(json, "canaries"))
      case "acked"                     => Expect.Acked(json.obj.get("withinSeconds").map(_.num.toLong))
      case "modalPayloadContains"      => Expect.ModalPayloadContains(strings(json, "values"))
      case other                       => throw ScenarioFormatException(source, s"unknown expect '$other'")

  private def wireEvent(json: ujson.Value, source: String): WireEvent =
    json("kind").str match
      case "message" =>
        WireEvent.Message(
          str(json, "wireId"),
          str(json, "chat"),
          str(json, "text"),
          json.obj.get("replyToMessageId").map(_.str)
        )
      case "command"  => WireEvent.Command(str(json, "wireId"), str(json, "chat"), str(json, "name"), str(json, "text"))
      case "callback" =>
        WireEvent.Callback(str(json, "wireId"), str(json, "chat"), str(json, "callback"), str(json, "sourceMessageId"))
      case "select" =>
        WireEvent.Select(str(json, "wireId"), str(json, "chat"), str(json, "callback"), str(json, "sourceMessageId"))
      case "modalSubmit" =>
        WireEvent.ModalSubmit(
          str(json, "wireId"),
          str(json, "chat"),
          str(json, "callback"),
          json.obj.get("fields").map(_.obj.map((k, v) => k -> v.str).toMap).getOrElse(Map.empty),
          str(json, "sourceMessageId")
        )
      case other => throw ScenarioFormatException(source, s"unknown wire event kind '$other'")

  private def fault(json: ujson.Value, source: String): VendorFault =
    json("fault").str match
      case "rateLimited"       => VendorFault.RateLimited(json("retryAfterSeconds").num.toLong)
      case "notModified"       => VendorFault.NotModified
      case "duplicateReaction" => VendorFault.DuplicateReaction
      case "deleteWindow"      => VendorFault.DeleteWindow
      case "editNotFound"      => VendorFault.EditNotFound
      case "blocked"           => VendorFault.Blocked
      case other               => throw ScenarioFormatException(source, s"unknown fault '$other'")

  /** The scenario RichText mini-language: `{"paragraph": [inline]}`, `{"bullets": [[inline]]}`, `{"code": "..."}` with
    * inline forms `{"text": s}`, `{"bold": s}`, `{"italic": s}`, `{"code": s}`, `{"lineBreak": true}`,
    * `{"repeat": [char, n]}`.
    */
  private def richText(json: ujson.Value, source: String): RichText =
    json.arr.toList.map { node =>
      node.obj.keys.toList match
        case List("paragraph") => Node.Paragraph(node("paragraph").arr.toList.map(inlineNode(_, source)))
        case List("bullets") => Node.BulletList(node("bullets").arr.toList.map(_.arr.toList.map(inlineNode(_, source))))
        case List("code")    => Node.CodeBlock(node("code").str)
        case keys            => throw ScenarioFormatException(source, s"unknown richText node $keys")
    }

  private def inlineNode(json: ujson.Value, source: String): Inline =
    json.obj.keys.toList match
      case List("text")      => Inline.Text(json("text").str)
      case List("bold")      => Inline.Bold(json("bold").str)
      case List("italic")    => Inline.Italic(json("italic").str)
      case List("code")      => Inline.Code(json("code").str)
      case List("lineBreak") => Inline.LineBreak
      case List("repeat")    =>
        val List(char, times) = json("repeat").arr.toList: @unchecked
        Inline.Text(char.str * times.num.toInt)
      case keys => throw ScenarioFormatException(source, s"unknown inline $keys")

  private def form(json: ujson.Value): RenderedForm =
    RenderedForm(
      str(json, "id"),
      str(json, "title"),
      json("fields").arr.toList.map { f =>
        RenderedField(
          str(f, "key"),
          str(f, "label"),
          FieldType.valueOf(str(f, "tpe")),
          f.obj.get("required").forall(_.bool),
          f.obj.get("placeholder").map(_.str),
          f.obj.get("options").map(_.arr.toList.map(_.str)).getOrElse(Nil)
        )
      },
      str(json, "submit")
    )

  /** Capability requirements are evaluated against the profile; transport traits against the wiring. */
  def unsatisfied(scenario: Scenario, wiring: AdapterWiring): List[String] =
    scenario.requires.filterNot { token =>
      val profile = wiring.profile
      token match
        case "edit"           => profile.editOwn != EditCapability.NoEdit
        case "delete"         => profile.deleteOwn != DeleteCapability.NoDelete
        case "react"          => profile.botReactions
        case "buttons"        => profile.buttons
        case "select"         => profile.select
        case "modal"          => profile.modal
        case "ephemeral"      => profile.ephemeral
        case "nativeCommands" => profile.nativeCommands
        case "transientAck"   => profile.transientAck
        case "ackDeadline"    => profile.ackDeadline.isDefined
        case "silent"         => profile.silentDelivery
        case other            => wiring.transportTraits(other)
    }
