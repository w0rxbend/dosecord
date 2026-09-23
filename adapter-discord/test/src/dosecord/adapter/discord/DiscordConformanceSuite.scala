package dosecord.adapter.discord

import dosecord.contracts.*
import dosecord.core.chat.CapabilityProfiles
import dosecord.tests.conformance.*

/** Suite A wiring for the Discord adapter on the fake wire: the full [[CapabilityProfiles.Discord]] profile (every
  * capability is native), and the Discord transport's traits — `redelivery` (interaction/message snowflakes), 
  * `httpStatus` (429 + Retry-After), `wireModal` (the raw modal payload).
  */
object DiscordWiring extends AdapterWiring:
  override val vendor: String = "discord"
  override val profile: CapabilityProfile = CapabilityProfiles.Discord
  override val transportTraits: Set[String] = Set("redelivery", "httpStatus", "wireModal")
  override val defaultChat: ChatRef = ChatRef("discord", "dm-user-1")

  override def start(scenario: String): AdapterUnderTest =
    val sink = RecordingSink()
    val server = DiscordFakeVendorServer(sink)
    val adapter = DiscordAdapter(
      server.transport,
      autocomplete = (_, _) => Nil,
      recordAckLatency = _ => (),
      now = () => server.clock,
      log = server.log
    )
    AdapterUnderTest(adapter, server, sink)

/** ROADMAP M2.1 acceptance: the Discord adapter passes every suite A scenario — event mapping with stable
  * `vendor_event_id`s, duplicate redelivery, deadline auto-ack from the fake clock, capability honesty, fault mapping,
  * markup goldens, text split, resume and log hygiene — against the fake Discord wire, never live JDA.
  */
class DiscordConformanceSuite extends munit.FunSuite:

  test("suite A: the discord adapter passes every applicable scenario"):
    val results = ConformanceSuite.suiteA(DiscordWiring)
    val failures = results.collect { case f: ScenarioResult.Failed => f }
    val skipped = results.collect { case s: ScenarioResult.Skipped => s }
    skipped.foreach(s => println(s"skipped ${s.name}: ${s.reason}"))
    assert(failures.isEmpty, failures.map(f => s"${f.name}: ${f.errors.mkString("; ")}").mkString("\n"))
    // The Discord profile is fully native and the fake wire carries every transport trait: all 14 scenarios apply.
    assertEquals(results.size - skipped.size, 14)
