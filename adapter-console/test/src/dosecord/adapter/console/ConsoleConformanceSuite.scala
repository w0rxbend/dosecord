package dosecord.adapter.console

import dosecord.contracts.*
import dosecord.core.chat.*
import dosecord.tests.conformance.*

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.StringReader
import java.time.Instant

/** Suite A wiring for the real console adapter: stdin/stdout stand-ins, the text-only console profile, no transport
  * traits (stdin has no redelivery, status codes or modal payloads).
  */
object ConsoleWiring extends AdapterWiring:
  override val vendor: String = "console"
  override val profile: CapabilityProfile = CapabilityProfiles.Console
  override val transportTraits: Set[String] = Set.empty
  override val defaultChat: ChatRef = ChatRef("console", "dm:owner")

  override def start(scenario: String): AdapterUnderTest =
    val sink = RecordingSink()
    val server = ConsoleFakeVendorServer(sink)
    val adapter = ConsoleAdapter("owner", server.reader, server.out, s"conf-$scenario", () => server.now)
    AdapterUnderTest(adapter, server, sink)

/** ROADMAP M0.13 acceptance for the console adapter: it passes every applicable suite A scenario, and suite B2 for the
  * console profile (quoted reply, bare digit gate, stale step, ephemeral downgrade, ordering) runs against the real
  * adapter through the mediator.
  */
class ConsoleConformanceSuite extends munit.FunSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")
  private val codec =
    CallbackCodec(CallbackKeys(CallbackKey(1, Array.tabulate[Byte](16)(i => (i + 1).toByte)), None))

  test("suite A: the console adapter passes every applicable scenario"):
    val results = ConformanceSuite.suiteA(ConsoleWiring)
    val failures = results.collect { case f: ScenarioResult.Failed => f }
    val skipped = results.collect { case s: ScenarioResult.Skipped => s }
    skipped.foreach(s => println(s"skipped ${s.name}: ${s.reason}"))
    assert(failures.isEmpty, failures.map(f => s"${f.name}: ${f.errors.mkString("; ")}").mkString("\n"))
    // 14 scenarios; the text-only console profile applies to 6 of them.
    assertEquals(results.size - skipped.size, 6)

  test("suite B2: mediator resolution for the console profile"):
    ox.supervised:
      val wiring = B2Wiring(
        vendor = "console",
        profile = CapabilityProfiles.Console,
        newAdapter = () =>
          val out = ByteArrayOutputStream()
          val printed = PrintStream(out)
          val adapter = ConsoleAdapter("owner", BufferedReader(StringReader("")), printed, "b2", () => t0)
          // PrintStream's methods synchronize on it; reading the buffer under the same monitor gives the
          // polling awaits a happens-before edge with the mediator's delivery threads.
          B2Adapter(adapter, () => printed.synchronized(ConsoleFakeVendorServer.parsePrinted(out.toString).map(_.text)))
        ,
        codec = codec
      )
      val results = SuiteB2.run(wiring)
      val failures = results.filterNot(_.passed)
      assert(
        failures.isEmpty,
        failures.map(r => s"${r.name}: ${r.errors.mkString("; ")}").mkString("\n")
      )
      assertEquals(results.size, 5)
