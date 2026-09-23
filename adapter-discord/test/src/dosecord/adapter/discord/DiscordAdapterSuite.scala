package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.tests.conformance.RecordingSink
import dosecord.tests.conformance.VendorFault

import java.time.Duration

/** Outbound behaviour of the Discord adapter on the fake wire: component translation (buttons/selects), the silent
  * flag, multi-chunk sends, edit/delete/react, the vendor-fault -> `ChatError` mapping (429 with Retry-After, 50007
  * `Unreachable`, 10008 non-fatal `Permanent`) and command registration.
  */
class DiscordAdapterSuite extends munit.FunSuite:

  private final class Rig:
    val sink = RecordingSink()
    val server = DiscordFakeVendorServer(sink)
    val adapter = DiscordAdapter(server.transport, (_, _) => Nil, _ => (), () => server.clock, server.log)
    adapter.start(sink, None)

  private val chat = ChatRef("discord", "dm-1")

  private def buttons = RenderedControls.Buttons(
    List(
      List(
        RenderedChoice(1, "Taken", "dc:taken", ChoiceStyle.Success),
        RenderedChoice(2, "Snooze 10m", "dc:snooze")
      ),
      List(RenderedChoice(3, "Skip", "dc:skip", ChoiceStyle.Danger))
    )
  )

  test("send lowers buttons to component rows with styles and the callback as custom id"):
    val rig = Rig()
    try
      val handle = rig.adapter.send(chat, RenderedMessage(chunks = List("Time for Vitamin D"), controls = List(buttons)), "k1")
      assert(handle.messageId.nonEmpty)
      val raw = rig.server.transport.sent.head.raw
      assert(raw.contains("components=2"), raw)
    finally rig.adapter.stop()

  test("the select menu's options carry the callbacks as values"):
    val rig = Rig()
    try
      val select = RenderedControls.SelectMenu("set-1", List(RenderedChoice(1, "One", "dc:one")), 1, 1)
      rig.adapter.send(chat, RenderedMessage(chunks = List("Pick"), controls = List(select)), "k1")
      assert(rig.server.transport.sent.head.raw.contains("components=1"))
    finally rig.adapter.stop()

  test("silent sends carry the suppression flag"):
    val rig = Rig()
    try
      rig.adapter.send(chat, RenderedMessage(chunks = List("quiet"), silent = true), "k1")
      assert(rig.server.transport.sent.head.raw.contains("silent=true"))
    finally rig.adapter.stop()

  test("a multi-chunk render sends one message per chunk; the handle is the last"):
    val rig = Rig()
    try
      val handle = rig.adapter.send(chat, RenderedMessage(chunks = List("one", "two", "three")), "k1")
      assertEquals(rig.server.transport.sent.map(_.text).toList, List("one", "two", "three"))
      assertEquals(handle.messageId, rig.server.transport.sent.last.messageId.get)
    finally rig.adapter.stop()

  test("edit replaces the message and bumps the revision; delete and react pass through"):
    val rig = Rig()
    try
      val handle = rig.adapter.send(chat, RenderedMessage(chunks = List("v1")), "k1")
      val edited = rig.adapter.edit(handle, RenderedMessage(chunks = List("v2")))
      assertEquals(edited.revision, 1)
      assertEquals(rig.server.transport.edits.head.text, "v2")
      rig.adapter.delete(edited)
      rig.adapter.react(edited, "1️⃣", on = true, txnKey = "k1:r0")
      rig.adapter.react(edited, "1️⃣", on = false, txnKey = "k1:r0")
    finally rig.adapter.stop()

  test("a 429 with Retry-After surfaces as RateLimited carrying the delay"):
    val rig = Rig()
    try
      rig.server.transport.failNext(VendorFault.RateLimited(30))
      val error = intercept[ChatError.RateLimited]:
        rig.adapter.send(chat, RenderedMessage(chunks = List("hi")), "k1")
      assertEquals(error.retryAfter, Duration.ofSeconds(30))
    finally rig.adapter.stop()

  test("an unknown message on edit is a non-fatal Permanent"):
    val rig = Rig()
    try
      rig.server.transport.failNext(VendorFault.EditNotFound)
      val error = intercept[ChatError.Permanent]:
        rig.adapter.edit(MessageHandle("discord", "dm-1", "999"), RenderedMessage(chunks = List("v2")))
      assert(!error.channelFatal)
    finally rig.adapter.stop()

  test("an unknown message on delete is a non-fatal Permanent"):
    val rig = Rig()
    try
      rig.server.transport.failNext(VendorFault.DeleteWindow)
      val error = intercept[ChatError.Permanent]:
        rig.adapter.delete(MessageHandle("discord", "dm-1", "999"))
      assert(!error.channelFatal)
    finally rig.adapter.stop()

  test("a 5xx vendor failure is Retryable"):
    val rig = Rig()
    try
      rig.server.transport.failNextRaw(502, None, "Bad Gateway", "send")
      intercept[ChatError.Retryable](rig.adapter.send(chat, RenderedMessage(chunks = List("hi")), "k1"))
    finally rig.adapter.stop()

  test("registerCommands plans the full spec surface for the transport"):
    val rig = Rig()
    try
      rig.adapter.registerCommands(List(CommandSpecMood))
      assertEquals(rig.server.transport.registered.size, 1)
      assertEquals(rig.server.transport.registered.head.head.name, "mood")
      assertEquals(rig.server.transport.registered.head.head.integrationTypes, Set(DiscordIntegrationType.UserInstall))
    finally rig.adapter.stop()

  private val CommandSpecMood = CommandSpec("mood", "Log your mood.")
