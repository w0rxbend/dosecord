package dosecord.core.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** Properties of `Evaluator.occurrences` required by ROADMAP M1.2, sampled over the seven M0.7 zones and windows in
  * 2025-01-01..2030-01-01 (so DST transitions of the sampled zones fall inside the windows).
  */
class EvaluatorProperties extends munit.ScalaCheckSuite:

  private val genZone: Gen[ZoneId] = Gen.oneOf(DstGolden.Zones)

  private val genWindow: Gen[(Instant, Instant)] =
    for
      start <- Gen.choose(1735689600L, 1893456000L) // 2025-01-01T00:00:00Z .. 2030-01-01T00:00:00Z
      hours <- Gen.choose(1L, 24L * 14L)
    yield (Instant.ofEpochSecond(start), Instant.ofEpochSecond(start + hours * 3600L))

  private val genRevision: Gen[ScheduleRevision] =
    for
      zone <- genZone
      rule <- Gen.oneOf(ScheduleGens.genFixedTimes, ScheduleGens.genAsNeeded)
    yield ScheduleRevision(rule, zone)

  property("determinism: the same revision and window always produce the same candidates"):
    forAll(genRevision, genWindow) { (revision, window) =>
      val (from, to) = window
      assertEquals(
        Evaluator.occurrences(revision, from, to),
        Evaluator.occurrences(revision, from, to)
      )
    }

  property("composability: occ(a, c) == occ(a, b) ++ occ(b, c) for b within [a, c]"):
    forAll(genRevision, genWindow) { (revision, window) =>
      val (a, c) = window
      // The window is at least one hour, so the midpoint lies strictly inside [a, c].
      val b = Instant.ofEpochSecond((a.getEpochSecond + c.getEpochSecond) / 2)
      assertEquals(
        Evaluator.occurrences(revision, a, c),
        Evaluator.occurrences(revision, a, b) ++ Evaluator.occurrences(revision, b, c)
      )
    }

  property("unique (local_date, slot_key) per schedule revision and window"):
    forAll(genRevision, genWindow) { (revision, window) =>
      val candidates = Evaluator.occurrences(revision, window._1, window._2)
      assertEquals(candidates.map(c => (c.localDate, c.slotKey)).distinct.size, candidates.size)
    }

  property("wall-time round trip outside transition days: local time -> instant -> the same wall time"):
    forAll(genRevision, genWindow) { (revision, window) =>
      val candidates = Evaluator.occurrences(revision, window._1, window._2)
      candidates.filter(_.dstKind == DstKind.None).foreach { c =>
        val zoned = c.scheduledFor.atZone(revision.zone)
        assertEquals(zoned.toLocalDate, c.localDate)
        assertEquals(zoned.toLocalTime, LocalTime.of(c.localTime.hour, c.localTime.minute))
      }
    }

  property("idempotent re-materialisation: recomputing the same window yields the same row set"):
    forAll(genRevision, genWindow) { (revision, window) =>
      val first = Evaluator.occurrences(revision, window._1, window._2)
      val second = Evaluator.occurrences(revision, window._1, window._2)
      // Dedupe semantics of DESIGN.md section 7.2's ON CONFLICT DO NOTHING: inserting a recomputation
      // over the existing rows (natural key (local_date, slot_key)) changes nothing.
      val rematerialized = (first ++ second).distinctBy(c => (c.localDate, c.slotKey))
      assertEquals(second, first)
      assertEquals(rematerialized, first)
    }
