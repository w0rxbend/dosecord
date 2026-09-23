package dosecord.tests.conformance

import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.CallbackKey
import dosecord.core.chat.CallbackKeys
import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.FakeAdapter

/** Suite B2 self-test: the five mediator-resolution scenarios pass for the console profile on the in-memory harness
  * (the real-console-adapter run lives in adapter-console's test sources).
  */
class SuiteB2Suite extends munit.FunSuite:

  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  test("suite B2 for the console profile"):
    ox.supervised:
      val wiring = B2Wiring(
        vendor = "console",
        profile = CapabilityProfiles.Console,
        newAdapter = () =>
          val recording = SynchronizedRecording(FakeAdapter(CapabilityProfiles.Console, "console"))
          B2Adapter(recording, () => recording.deliveredTexts)
        ,
        codec = codec
      )
      val results = SuiteB2.run(wiring)
      val failures = results.filterNot(_.passed)
      assert(
        failures.isEmpty,
        failures.map(r => s"${r.name}: ${r.errors.mkString("; ")}").mkString("\n")
      )
      assertEquals(
        results.map(_.name).toSet,
        Set("quoted-reply", "bare-digit-gate", "stale-step", "ephemeral-downgrade", "ordering")
      )
