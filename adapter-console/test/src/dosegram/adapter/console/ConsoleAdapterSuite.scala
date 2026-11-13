package dosegram.adapter.console

import dosegram.contracts.*

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The suite-A scenarios applicable to the text-only console profile (ROADMAP M0.12c; M0.13 formalises the runner):
  * event mapping with stable vendor_event_ids, duplicate determinism, capability honesty, text split and numbered
  * reply parsing.
  */
class ConsoleAdapterSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val chat = ChatRef("console", "dm:owner")

  private def newAdapter(lines: String = "", session: String = "s1"): (ConsoleAdapter, ByteArrayOutputStream) =
    val bytes = ByteArrayOutputStream()
    val adapter =
      ConsoleAdapter("owner", BufferedReader(StringReader(lines)), PrintStream(bytes), session, () => t0)
    (adapter, bytes)

  private def await(cond: => Boolean, clue: String): Unit =
    val deadline = System.nanoTime() + 10_000_000_000L
    var ok = cond
    while !ok && System.nanoTime() < deadline do
      Thread.sleep(10)
      ok = cond
    assert(ok, clue)

  test("vendor and capability profile are the text-only console profile"):
    val (adapter, _) = newAdapter()
    assertEquals(adapter.vendor, "console")
    assertEquals(adapter.capabilities.nativeCommands, false)
    assertEquals(adapter.capabilities.buttons, false)
    assertEquals(adapter.capabilities.editOwn, EditCapability.NoEdit)
    assertEquals(adapter.capabilities.deleteOwn, DeleteCapability.NoDelete)
    assertEquals(adapter.capabilities.markup, Markup.Plain)

  test("a plain line maps to MessageReceived with a stable vendor_event_id, the CONSOLE_USER_ID actor and a cursor"):
    val (adapter, _) = newAdapter(session = "s1")
    val event = adapter.eventFor("  hello there  ")
    assertEquals(event.vendor, "console")
    assertEquals(event.vendorEventId, "console:msg:s1:1")
    assertEquals(event.actor, PlatformIdentity("console", "owner"))
    assertEquals(event.chat, chat)
    assertEquals(event.cursor, Some("1"))
    assertEquals(event.receivedAt, t0)
    assertEquals(event.body, Inbound.MessageReceived("hello there", None, truncated = false))

  test("duplicate: the id is a pure function of (session, sequence); a distinct line is a distinct wire event"):
    val (first, _) = newAdapter(session = "s1")
    val (replay, _) = newAdapter(session = "s1")
    assertEquals(
      first.eventFor("hello").vendorEventId,
      replay.eventFor("hello").vendorEventId,
      "the same wire position mints the same id — a replay keeps its id"
    )
    assertEquals(first.eventFor("hello").vendorEventId, "console:msg:s1:2", "a second line is a second wire event")

    val (other, _) = newAdapter(session = "s2")
    assertNotEquals(
      other.eventFor("hello").vendorEventId,
      "console:msg:s1:1",
      "the session component keeps ids unique across restarts"
    )

  test("resumeFrom continues the sequence instead of re-minting ids"):
    val (adapter, _) = newAdapter(session = "s1")
    adapter.start(_ => true, Some("41"))
    assertEquals(adapter.eventFor("after restart").vendorEventId, "console:msg:s1:42")
    adapter.stop()

  // ADR-015 CON-02: every new outbound shape has a console rendering.
  test("a photo card with link choices prints its text equivalent, caption and legend"):
    val (adapter, out) = newAdapter()
    val card = OutboundMessage(
      body = List(Node.Paragraph(List(Inline.Text("Your week.")))),
      blocks = List(
        Block.Choices(
          ChoiceSet(
            id = "card.nav",
            choices = List(
              Choice.callback("Stats", "dc:stats"),
              Choice.openApp("Open app", AppRoute("today")),
              Choice.openUrl("Privacy notice", "https://example.org/privacy")
            )
          )
        )
      ),
      attachment =
        Some(AttachmentRef(MediaKind.Photo, "asset-week-45", "image/png", "Week 45: 12 of 14 doses taken.")),
      dedupeKey = "d",
      correlationId = "c"
    )
    val (ops, _) = dosegram.core.chat.Renderer.render(card, chat, adapter.capabilities, "k1")
    ops.foreach { case VendorOp.Send(to, rendered, key, _) => adapter.send(to, rendered, key); case _ => () }
    assertEquals(
      out.toString,
      "[#1] [image: Week 45: 12 of 14 doses taken.]\n\nYour week.\n\n" +
        "1) Stats [app: today] Open app [link: https://example.org/privacy] Privacy notice\n"
    )

  test("capability honesty: the console sends no media"):
    val (adapter, _) = newAdapter()
    val photo = AttachmentRef(MediaKind.Photo, "asset-1", "image/png", "A chart.")
    intercept[ChatError.Unsupported](adapter.send(chat, RenderedMessage(chunks = Nil, attachment = Some(photo)), "k1"))

  test("media input: `+photo <id> [caption]` and `+document <id> [caption]` are received media"):
    val (adapter, _) = newAdapter()
    assertEquals(
      adapter.eventFor("+photo file-1 lunch with pasta").body,
      Inbound.MediaReceived(MediaKind.Photo, MediaRef("file-1"), Some("lunch with pasta"))
    )
    assertEquals(
      adapter.eventFor("+document file-2").body,
      Inbound.MediaReceived(MediaKind.Document, MediaRef("file-2"), None)
    )
    assertEquals(
      adapter.eventFor("+photo").body,
      Inbound.MessageReceived("+photo", None, truncated = false),
      "a media line without an id is plain text"
    )

  test("deep link input: `/start <payload>` starts the conversation with the payload"):
    val (adapter, _) = newAdapter()
    assertEquals(
      adapter.eventFor("/start invite_abc-1").body,
      Inbound.ConversationStarted(Some(StartPayload("invite_abc-1")))
    )
    assertEquals(
      adapter.eventFor("/start").body,
      Inbound.CommandInvoked("start", Map.empty, "/start"),
      "a bare /start stays a command"
    )
    assertEquals(
      adapter.eventFor("/start not a payload!").body,
      Inbound.CommandInvoked("start", Map.empty, "/start not a payload!"),
      "text outside the payload grammar is not a payload"
    )

  test("numbered reply parsing: `#<n> <reply>` quotes the printed message n"):
    val (adapter, out) = newAdapter()
    val h1 = adapter.send(chat, RenderedMessage(chunks = List("one")), sendKey = "k1")
    adapter.send(chat, RenderedMessage(chunks = List("two")), sendKey = "k2")
    val h3 = adapter.send(chat, RenderedMessage(chunks = List("three")), sendKey = "k3")
    assertEquals(out.toString, "[#1] one\n[#2] two\n[#3] three\n", "every outbound message is numbered")
    assertEquals(h1, MessageHandle("console", "dm:owner", "1"))

    assertEquals(
      adapter.eventFor("#3 1").body,
      Inbound.MessageReceived("1", Some(h3), truncated = false),
      "`#3 1` produces MessageReceived(replyTo = handle 3)"
    )
    assertEquals(
      adapter.eventFor("#2 taken with lunch").body,
      Inbound.MessageReceived("taken with lunch", Some(MessageHandle("console", "dm:owner", "2")), truncated = false),
      "the reply text after the target is carried verbatim"
    )
    assertEquals(
      adapter.eventFor("#9 still works").body,
      Inbound.MessageReceived("still works", Some(MessageHandle("console", "dm:owner", "9")), truncated = false),
      "an unknown number still targets handle 9; the mediator's choice_map lookup decides"
    )
    assertEquals(
      adapter.eventFor("#3").body,
      Inbound.MessageReceived("#3", None, truncated = false),
      "a target without a reply is a plain message"
    )
    assertEquals(
      adapter.eventFor("3").body,
      Inbound.MessageReceived("3", None, truncated = false),
      "a bare digit has no target; the mediator's latest-prompt gate applies"
    )

  test("a slash line maps to CommandInvoked carrying the raw line"):
    val (adapter, _) = newAdapter()
    assertEquals(
      adapter.eventFor("/mood 8 slept well #sleep").body,
      Inbound.CommandInvoked("mood", Map.empty, "/mood 8 slept well #sleep")
    )

  test("capability honesty: edit and delete are Unsupported; registerCommands is a no-op"):
    val (adapter, _) = newAdapter()
    val handle = MessageHandle("console", "dm:owner", "1")
    intercept[ChatError.Unsupported](adapter.edit(handle, RenderedMessage(chunks = List("x"))))
    intercept[ChatError.Unsupported](adapter.delete(handle))
    adapter.registerCommands(List(CommandSpec("mood", "log a mood")))
    adapter.registerCommands(Nil)

  test("renderText splits at the profile's maxText; send prints every chunk"):
    val (adapter, out) = newAdapter()
    val chunks = adapter.renderText(List(Node.Paragraph(List(Inline.Text("x" * 9000)))))
    assertEquals(chunks.map(_.length), List(4000, 4000, 1000))

    adapter.send(chat, RenderedMessage(chunks = chunks.take(2)), sendKey = "k")
    assertEquals(out.toString, s"[#1] ${chunks(0)}\n${chunks(1)}\n")

  test("start drains stdin into the sink, in order, until EOF"):
    val (adapter, _) = newAdapter(lines = "one\n\n#1 two\n/mood 8\n", session = "s1")
    val received = ConcurrentLinkedQueue[InboundEvent]()
    adapter.start(event => { received.add(event); true }, None)
    await(received.size == 3, "every non-empty line reached the sink")

    val events = received.asScala.toList
    assertEquals(events.map(_.vendorEventId), List("console:msg:s1:1", "console:msg:s1:2", "console:msg:s1:3"))
    assertEquals(
      events.map(_.body),
      List(
        Inbound.MessageReceived("one", None, truncated = false),
        Inbound.MessageReceived("two", Some(MessageHandle("console", "dm:owner", "1")), truncated = false),
        Inbound.CommandInvoked("mood", Map.empty, "/mood 8")
      ),
      "blank lines produce no event"
    )
    adapter.stop()
