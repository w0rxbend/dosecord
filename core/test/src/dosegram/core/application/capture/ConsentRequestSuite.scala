package dosegram.core.application.capture

import dosegram.core.chat.CallbackCodec
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.ports.ConsentPurpose
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The value of a `consent.step` token carries the stage, the purpose and the pending input (BOT_UX BUX-CONS-04). It
  * must survive the round trip through the 48-bit value of a callback token for every input the chat can keep.
  */
class ConsentRequestSuite extends munit.ScalaCheckSuite:

  private val pendings: Gen[Pending] = Gen.oneOf(
    Gen.const(Pending.WaterCard),
    Gen.choose(HydrationBounds.MinEntryMl, HydrationBounds.MaxEntryMl).map(Pending.WaterAdd(_)),
    Gen.const(Pending.MoodPrompt),
    Gen.choose(1, 5).map(Pending.MoodCheckIn(_)),
    Gen.const(Pending.Stats),
    Gen.const(Pending.WaterImage),
    Gen.const(Pending.DayImage)
  )

  private val requests: Gen[ConsentRequest] =
    for
      stage <- Gen.oneOf(ConsentStage.values.toSeq)
      purpose <- Gen.oneOf(ConsentPurpose.values.toSeq)
      pending <- pendings
    yield ConsentRequest(stage, purpose, pending)

  property("a request survives the round trip through a token value"):
    forAll(requests): request =>
      assert(request.value >= 0 && request.value <= CallbackCodec.MaxValue, s"value ${request.value}")
      assertEquals(ConsentRequest.of(request.value), Some(request))

  test("every purpose of the ledger has a wire code, and the codes are distinct"):
    val codes = ConsentPurpose.values.toList.map(ConsentRequest.codeOf)
    assertEquals(codes.distinct, codes)
    ConsentPurpose.values.foreach(purpose =>
      assertEquals(ConsentRequest.purposeOf(ConsentRequest.codeOf(purpose).toLong), Some(purpose))
    )

  test("a value with an unknown purpose or pending kind is no request"):
    assertEquals(ConsentRequest.of(0L), None)
    assertEquals(ConsentRequest.of(ConsentRequest(ConsentStage.Ask, ConsentPurpose.Mood, Pending.Stats).value | (15L << 6)), None)
