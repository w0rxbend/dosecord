package dosecord.infra

class TzdbGuardSuite extends munit.FunSuite:
  import TzdbGuard.Verdict

  test("version comparison"):
    assert(TzdbGuard.atLeast("2026c", "2026c"))
    assert(TzdbGuard.atLeast("2026c", "2025b"))
    assert(TzdbGuard.atLeast("2027a", "2026c"))
    assert(!TzdbGuard.atLeast("2026b", "2026c"))
    assert(!TzdbGuard.atLeast("2025b", "2026c"))
    assert(!TzdbGuard.atLeast("garbage", "2026c"))

  test("verdicts: fail below floor, warn below target"):
    assertEquals(TzdbGuard.check(None), Verdict.Unsupported("runtime tzdb version is unreadable"))
    assertEquals(TzdbGuard.check(Some("2025a")), Verdict.Unsupported("tzdb 2025a is below floor 2025b"))
    assertEquals(TzdbGuard.check(Some("2025b")), Verdict.Outdated("2025b"))
    assertEquals(TzdbGuard.check(Some("2026b")), Verdict.Outdated("2026b"))
    assertEquals(TzdbGuard.check(Some("2026c")), Verdict.Ok("2026c"))
    assertEquals(TzdbGuard.check(Some("2027a")), Verdict.Ok("2027a"))

  test("the current runtime resolves a version at or above the floor"):
    val version = TzdbGuard.resolveRuntimeVersion()
    assert(version.nonEmpty, "runtime tzdb version unreadable on this JDK")
    assert(
      TzdbGuard.atLeast(version.get, TzdbGuard.FloorVersion),
      s"runtime tzdb ${version.get} below floor ${TzdbGuard.FloorVersion} — rebuild with a newer tzdata"
    )

  test("assertSupported returns the runtime version and does not throw on this JDK"):
    var logged = List.empty[String]
    val version = TzdbGuard.assertSupported(msg => logged = logged :+ msg)
    assert(version.nonEmpty)
    assertEquals(logged.size, 1)
    // temurin 25.0.4 ships 2026b (< target 2026c), so a warning is expected until the image override ships newer tzdata.
    if TzdbGuard.atLeast(version, TzdbGuard.TargetVersion) then assert(logged.head.startsWith("tzdb "))
    else assert(logged.head.startsWith("WARNING: tzdb "))
