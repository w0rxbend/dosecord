package dosecord.core.chat

import dosecord.contracts.*

class FakeAdapterSuite extends munit.FunSuite:

  private def rendered: RenderedMessage =
    RenderedMessage(chunks = List("hello"))

  private val chat = ChatRef("fake", "dm:u1")

  test("send records the op and returns a deterministic handle"):
    val adapter = FakeAdapter(CapabilityProfiles.Console, "console")
    val h1 = adapter.send(chat, rendered, "k1")
    val h2 = adapter.send(chat, rendered, "k2")
    assertEquals(h1, MessageHandle("console", "dm:u1", "m1"))
    assertEquals(h2.messageId, "m2")
    assertEquals(adapter.ops.size, 2)
    assertEquals(adapter.sent.size, 2)

  test("edit bumps the revision; delete removes the message"):
    val adapter = FakeAdapter(CapabilityProfiles.Discord, "discord")
    val handle = adapter.send(chat, rendered, "k1")
    val edited = adapter.edit(handle, rendered)
    assertEquals(edited.revision, 1)
    adapter.delete(handle)
    assertEquals(adapter.sent.size, 0)

  test("capability honesty: ops the profile does not claim raise Unsupported"):
    val console = FakeAdapter(CapabilityProfiles.Console, "console")
    intercept[ChatError.Unsupported](console.react(MessageHandle("console", "dm:u1", "m1"), "1️⃣", true, "t1"))
    intercept[ChatError.Unsupported](console.edit(MessageHandle("console", "dm:u1", "m1"), rendered))
    intercept[ChatError.Unsupported](console.delete(MessageHandle("console", "dm:u1", "m1")))

  test("failNext throws the injected error once, then recovers"):
    val adapter = FakeAdapter(CapabilityProfiles.Discord, "discord")
    adapter.failNext(ChatError.Retryable("boom"))
    intercept[ChatError.Retryable](adapter.send(chat, rendered, "k1"))
    adapter.send(chat, rendered, "k2")
    assertEquals(adapter.ops.size, 1)

  test("resolveChat and renderText follow the profile"):
    val adapter = FakeAdapter(CapabilityProfiles.Telegram, "telegram")
    assertEquals(adapter.resolveChat(PlatformIdentity("telegram", "u1")), ChatRef("telegram", "dm:u1"))
    val long = List(Node.Paragraph(List(Inline.Text("x" * 5000))))
    val chunks = adapter.renderText(long)
    assert(chunks.size == 2 && chunks.forall(_.length <= 4096))

  test("start/stop track lifecycle and the resume cursor"):
    val adapter = FakeAdapter(CapabilityProfiles.Console, "console")
    adapter.start(_ => true, Some("cursor:7"))
    assert(adapter.isRunning)
    assertEquals(adapter.resumeFrom, Some("cursor:7"))
    adapter.stop()
    assert(!adapter.isRunning)
end FakeAdapterSuite
