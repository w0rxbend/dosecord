package dosecord.core.scheduling

import dosecord.contracts.MessageHandle
import dosecord.contracts.RenderedChoice
import dosecord.contracts.RenderedControls
import dosecord.contracts.RenderedMessage

import java.time.Duration

class OutboxDispatcherSuite extends munit.FunSuite:

  test("ops_done bitmap: bit 0 is the primary send, bit i+1 reaction i"):
    assert(!OutboxDispatcher.opsDoneHas(0, 0))
    assert(OutboxDispatcher.opsDoneHas(1, 0))
    assert(!OutboxDispatcher.opsDoneHas(1, 1))
    assert(OutboxDispatcher.opsDoneHas(3, 0) && OutboxDispatcher.opsDoneHas(3, 1))
    assert(OutboxDispatcher.opsDoneHas(7, 2))
    assert(!OutboxDispatcher.opsDoneHas(7, 3))

  test("handle codec round-trips and splits on the last separator (chat ids may contain ':')"):
    val handle = MessageHandle("fake", "chat:room:1", "m42")
    assertEquals(OutboxDispatcher.decodeHandle("fake", OutboxDispatcher.encodeHandle(handle)), handle)
    assertEquals(OutboxDispatcher.encodeHandle(MessageHandle("fake", "chat-1", "m1")), "chat-1:m1")
    intercept[IllegalArgumentException](OutboxDispatcher.decodeHandle("fake", "no-separator"))

  test("backoff grows exponentially from 5 s and is capped at 30 min"):
    assertEquals(OutboxDispatcher.backoff(1), Duration.ofSeconds(5))
    assertEquals(OutboxDispatcher.backoff(2), Duration.ofSeconds(10))
    assertEquals(OutboxDispatcher.backoff(3), Duration.ofSeconds(20))
    assertEquals(OutboxDispatcher.backoff(8), Duration.ofSeconds(640))
    assertEquals(OutboxDispatcher.backoff(20), Duration.ofMinutes(30))

  test("reactionsOf extracts the reaction sub-ops of numbered controls in order"):
    val choices = List(RenderedChoice(index = 1, label = "one", callback = "dc:a", emoji = Some("✅")))
    val rendered = RenderedMessage(
      chunks = List("body"),
      controls = List(
        RenderedControls.NoControls,
        RenderedControls.Numbered("cs1", choices, List("✅", "⏰", "❌"))
      )
    )
    assertEquals(OutboxDispatcher.reactionsOf(rendered), List("✅", "⏰", "❌"))
    assertEquals(OutboxDispatcher.reactionsOf(RenderedMessage(chunks = List("body"))), Nil)
