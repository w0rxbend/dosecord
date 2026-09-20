# Spike note: DST materialiser primitives (M0.7)

Slice: M0.7 — DST materialiser primitives (risk-first, code kept). Deps: M0.1 (green).
Date: 2026-09-20. Decision records read: ADR-011; docs/DESIGN.md section 7.2.

## Approach

`Dst.resolveLocal` in `core/domain` (`dosecord.core.domain.Dst`) follows the
DESIGN.md section 7.2 / ADR-011 policy exactly, built on
`ZoneRules.getTransition(localDateTime)`:

- no transition at the wall time → `atZone`, classified `DstKind.None`;
- transition `isGap` → `ZonedDateTime.ofLocal(ldt, zone, null)`, which shifts
  forward by the gap length (same result as Python zoneinfo `fold=0` and
  Temporal `compatible`), classified `DstKind.Gap`;
- otherwise (overlap) → `ZonedDateTime.ofLocal(ldt, zone, offsetBefore)`, the
  earlier of the two instants, classified `DstKind.Fold`.

`DstKind.dbValue` (`none`/`gap`/`fold`) matches the `dst_kind` CHECK constraint
of DESIGN.md section 8.

Golden rows are *generated*, never hand-written: `DstGolden` (test sources)
enumerates every real transition of the seven zones in 2000-01-01..2031-01-01
via `ZoneRules.nextTransition` (not `getTransitions`, which omits everything
under a recurring last rule — Kyiv and London would have stopped at 1997) and
samples the middle of every gap and every fold; zones without transitions in
the window (Asia/Kolkata) fall back to their last four historical transitions.
Three fixed off-transition wall times per zone cover the plain path. The
expected instant and kind of every row are derived directly from the
`ZoneOffsetTransition` data (gap: shift forward by the gap length under the
offset after; fold: the offset before), independent of `resolveLocal`. The
committed file is `core/test/resources/dst-golden.txt`; regenerate with
`./mill core.test.runMain dosecord.core.domain.dstGoldenGenerate core/test/resources/dst-golden.txt`.

## tzdb version observed

The runtime tzdb version is read from `$java.home/lib/tzdb.dat` (JDK 9+ header)
by `TzdbVersion` and asserted in tests:

- Mill build/test JVM (coursier-provisioned zulu 21.0.10): **2025b**.
- Newest locally installed JDK (temurin 25.0.4, sdkman `current`): **2026b**.

The committed goldens were generated under 2025b and re-generated under 2026b:
the 335 rows are **identical** (only the `# tzdb=` header line differs), so the
golden equality test normalises that header line and compares rows — a JDK
patch upgrade that changes none of the seven zones' 2000-2030 transitions does
not break the build, while a tzdb change that alters any of them fails the
suite until the goldens are regenerated. A floor test asserts tzdb >= 2025b.

## Golden coverage (335 rows)

| Zone | Rows | Notes |
|---|---|---|
| Europe/Kyiv | 65 | 1 h gaps at 03:xx, folds at 03:30, incl. both named 2026 rows |
| Europe/London | 65 | 1 h gaps at 01:xx, folds at 01:30 |
| America/New_York | 65 | 1 h gaps at 02:xx, folds at 02:30 |
| Australia/Lord_Howe | 65 | 30-minute transitions, e.g. 2026-10-04 02:15 -> 02:45 |
| Asia/Kolkata | 7 | no transitions since 1945; last four historical rows (1941-1945) |
| Pacific/Apia | 26 | incl. the 2011-12-30 24 h date-line gap (12:00 -> next day 12:00) |
| America/Sao_Paulo | 42 | DST abolished 2019; rows cover 2000-2019 |

Plus a ScalaCheck property: for random wall times (2000-2030, second precision)
in the seven zones, `resolveLocal` agrees with the tzdb transition data (kind,
wall-time round trip outside transition days, exact gap shift, earlier-instant
fold).

## Go criteria

1. Kyiv 2026-03-29 03:30 -> 04:30 local with `gap`: **PASS**
   (`2026-03-29T01:30:00Z`, test "go criterion: Kyiv 2026-03-29 03:30 resolves
   to 04:30 local with gap").
2. Kyiv 2026-10-25 03:30 -> the earlier instant with `fold`: **PASS**
   (`2026-10-25T00:30:00Z`, the first of the two 03:30s, at +03:00).
3. Lord Howe 02:15 on its 30-minute transition day (2026-10-04) -> 02:45:
   **PASS** (`2026-10-03T15:45:00Z`, +11:00).
4. JDK tzdb >= 2026c: **NOT MET on any available runtime** — build/test JVM
   ships 2025b, newest local JDK ships 2026b. Impact assessment: tzdata 2026c
   (released 2026-07-08) changes only Alberta, British Columbia and Morocco;
   none of the seven golden zones is affected, and the goldens are identical
   under 2025b and 2026b. The criterion is encoded as an expected-failure test
   (`"go criterion: runtime tzdb >= 2026c".fail`), which flips to red the day
   the runtime tzdb reaches 2026c, forcing promotion to a plain assertion.
5. Golden rows generated from real `ZoneRules` transitions for all seven
   zones, committed and validated against `resolveLocal`: **PASS** (335 rows,
   `DstGoldenSuite`, 337 tests).

## Verdict

**Go, with one recorded deviation.** The `resolveLocal` primitive and the
generated seven-zone goldens work exactly as ADR-011/DESIGN.md specify on every
available runtime, so the code and tests stay (M1.2 builds on them). The tzdb
version criterion fails as written: per the roadmap's no-go remedy, the runtime
image gains a tzdata override step and the tzdb version becomes a startup
assertion — that work belongs to M0.5 (image) / M0.11 (metric), not this slice.

## Open risks / follow-ups

- The Mill launcher provisions its own JVM (zulu 21.0.10, tzdb 2025b) and
  ignores `JAVA_HOME`; the project runtime JDK is therefore unpinned, which is
  exactly the failure mode ADR-011's "tzdb tracked as a metric" guards against.
  Recommend pinning `jvmVersion`/`javaHome` in `build.mill` (deferred to the
  orchestrator; this slice may not touch `build.mill`) and adding the tzdb
  startup assertion + image tzdata override per the roadmap's no-go remedy.
- A government rule change inside the 48 h horizon is covered by DESIGN.md's
  nightly verify pass; the golden drift test is the code-side tripwire.
