package dosecord.tests.conformance

import ox.Ox

/** The adapter conformance entry point (ROADMAP M0.13): every `adapter-*` module's test sources call these two methods
  * with their wiring, so "supports vendor X" is mechanically `./mill <adapter>.test` green. Suite A runs the JSON
  * scenarios against the adapter on a fake vendor wire; suite B2 runs the mediator-resolution scenarios for the
  * adapter's profile.
  */
object ConformanceSuite:

  def suiteA(wiring: AdapterWiring): List[ScenarioResult] =
    SuiteARunner.run(wiring)

  def suiteB2(wiring: B2Wiring)(using Ox): List[SuiteB2.Result] =
    SuiteB2.run(wiring)
