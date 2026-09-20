package dosecord.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ResolveLocalSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val lordHowe = ZoneId.of("Australia/Lord_Howe")

  test("go criterion: Kyiv 2026-03-29 03:30 resolves to 04:30 local with gap"):
    val (instant, kind) = Dst.resolveLocal(LocalDate.of(2026, 3, 29), LocalTime.of(3, 30), kyiv)
    assertEquals(kind, DstKind.Gap)
    assertEquals(instant, Instant.parse("2026-03-29T01:30:00Z"))
    assertEquals(instant.atZone(kyiv).toLocalTime, LocalTime.of(4, 30))

  test("go criterion: Kyiv 2026-10-25 03:30 resolves to the earlier instant with fold"):
    val (instant, kind) = Dst.resolveLocal(LocalDate.of(2026, 10, 25), LocalTime.of(3, 30), kyiv)
    assertEquals(kind, DstKind.Fold)
    // 03:30 under +03:00, the offset before the transition, i.e. the first of the two 03:30s.
    assertEquals(instant, Instant.parse("2026-10-25T00:30:00Z"))
    assertEquals(instant.atZone(kyiv).toLocalTime, LocalTime.of(3, 30))

  test("go criterion: Lord Howe 02:15 on its 30-minute transition day resolves to 02:45"):
    val (instant, kind) = Dst.resolveLocal(LocalDate.of(2026, 10, 4), LocalTime.of(2, 15), lordHowe)
    assertEquals(kind, DstKind.Gap)
    assertEquals(instant.atZone(lordHowe).toLocalTime, LocalTime.of(2, 45))
    assertEquals(instant, Instant.parse("2026-10-03T15:45:00Z"))

  test("a local time away from any transition resolves with DstKind.None and keeps the wall time"):
    val (instant, kind) = Dst.resolveLocal(LocalDate.of(2026, 6, 15), LocalTime.of(9, 0), kyiv)
    assertEquals(kind, DstKind.None)
    assertEquals(instant.atZone(kyiv).toLocalTime, LocalTime.of(9, 0))

  test("runtime tzdb version is recorded"):
    println(s"JDK tzdb version: ${TzdbVersion.runtimeVersion()}")

  test("runtime tzdb is at least 2025b, the floor this slice's goldens were verified against"):
    val version = TzdbVersion.runtimeVersion()
    assert(TzdbVersion.atLeast(version, "2025b"), s"runtime tzdb is $version")

  test("go criterion: runtime tzdb >= 2026c".fail):
    // Known deviation: the Mill build/test JVM (zulu 21.0.10) ships tzdb 2025b and the newest
    // locally installed JDK (temurin 25.0.4) ships 2026b. tzdata 2026c changes only Alberta,
    // British Columbia and Morocco — none of the seven golden zones. Recorded in
    // docs/spikes/dst-primitives.md. This expected-failure flips (and must be promoted to a
    // plain assertion) once the runtime tzdb reaches 2026c.
    val version = TzdbVersion.runtimeVersion()
    assert(TzdbVersion.atLeast(version, "2026c"), s"runtime tzdb is $version")
