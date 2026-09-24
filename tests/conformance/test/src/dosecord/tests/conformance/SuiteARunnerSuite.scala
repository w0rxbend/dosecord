package dosecord.tests.conformance

import dosecord.core.chat.CapabilityProfiles
import dosecord.core.chat.FakeAdapter

/** Runner self-tests: the JSON catalog loads, an honest adapter passes every applicable scenario, and a deliberately
  * lying profile (claims `buttons` and `select` but rejects them) fails capability honesty — the M0.13 acceptance
  * demonstration.
  */
class SuiteARunnerSuite extends munit.FunSuite:

  test("the scenario catalog loads: 16 scenarios with unique names"):
    val scenarios = Scenario.loadAll()
    assertEquals(scenarios.size, 16)
    assertEquals(scenarios.map(_.name).distinct.size, scenarios.size)

  test("an honest text-only adapter passes every applicable scenario"):
    val wiring = StubWiring("fake", CapabilityProfiles.Console, Set("redelivery", "httpStatus", "wireModal"), () =>
      FakeAdapter(CapabilityProfiles.Console, "fake")
    )
    val results = SuiteARunner.run(wiring)
    val failures = results.collect { case f: ScenarioResult.Failed => f }
    val skipped = results.collect { case s: ScenarioResult.Skipped => s }
    assert(failures.isEmpty, failures.map(f => s"${f.name}: ${f.errors.mkString("; ")}").mkString("\n"))
    // The text-only profile skips exactly the scenarios that need capabilities it does not claim.
    assertEquals(
      skipped.map(_.name).toSet,
      Set(
        "deadline-auto-ack",
        "not-modified-success",
        "duplicate-reaction-success",
        "delete-window-permanent",
        "edit-not-found-permanent",
        "raw-modal-payload",
        "component-routing",
        "modal-submit-routing"
      )
    )

  test("capability honesty passes an adapter that delivers what its profile claims"):
    val wiring = StubWiring("honest", CapabilityProfiles.Console.copy(buttons = true, select = true), Set.empty, () =>
      HonestButtonsAdapter()
    )
    val results = SuiteARunner.run(wiring)
    val honesty = results.collectFirst { case f: ScenarioResult.Failed if f.name == "capability-honesty" => f }
    assert(honesty.isEmpty, honesty.map(_.errors.mkString("; ")).getOrElse(""))

  test("capability honesty fails a deliberately lying profile (claims buttons, rejects them)"):
    val wiring = StubWiring("liar", CapabilityProfiles.Console.copy(buttons = true, select = true), Set.empty, () =>
      LyingButtonsAdapter()
    )
    val results = SuiteARunner.run(wiring)
    val honesty = results.collectFirst { case f: ScenarioResult.Failed if f.name == "capability-honesty" => f }
    assert(honesty.isDefined, "the lying profile must fail capability honesty")
    assert(
      honesty.get.errors.exists(_.contains("buttons")),
      s"the failure names the dishonest capability: ${honesty.get.errors}"
    )
