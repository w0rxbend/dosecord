package dosegram.core.domain

/** ARCHITECTURE section 11.3, ROADMAP G4.4: "a statistic below its minimum sample is `Insufficient` and no caller can
  * format it as a number". This suite lives outside `core.domain.insights` on purpose: from here neither the `Shown`
  * constructor nor the gate is reachable, so the only source of a shown value is a statistic function.
  */
class StatConstructionSuite extends munit.FunSuite:

  test("Shown has no apply outside the insights package"):
    val errors = compileErrors(
      """import dosegram.core.domain.insights.*
         Stat.Shown(1, 1, DateRange.single(java.time.LocalDate.EPOCH), Basis.Recorded)"""
    )
    assert(clue(errors).contains("does not take parameters"))

  test("Shown cannot be constructed with new outside the insights package"):
    val errors = compileErrors(
      """import dosegram.core.domain.insights.*
         new Stat.Shown(1, 1, DateRange.single(java.time.LocalDate.EPOCH), Basis.Recorded)"""
    )
    assert(clue(errors).contains("cannot be accessed"))

  test("the gate cannot be called outside the insights package"):
    val errors = compileErrors(
      """import dosegram.core.domain.insights.*
         Stat.gated(9, MinimumSample.AnyRecord, DateRange.single(java.time.LocalDate.EPOCH), Basis.Recorded)(1)"""
    )
    assert(clue(errors).contains("cannot be accessed"))

  test("a shown value cannot be copied into another value outside the insights package"):
    val errors = compileErrors(
      """import dosegram.core.domain.insights.*
         def forge(shown: Stat.Shown[Int]): Stat[Int] = shown.copy(value = 100)"""
    )
    assert(clue(errors).contains("cannot be accessed"))
end StatConstructionSuite
