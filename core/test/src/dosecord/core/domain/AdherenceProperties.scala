package dosecord.core.domain

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** ROADMAP M1.4b acceptance properties over the adherence maths: `0 <= adherence <= 1`, counts sum to resolved, and
  * `unknown` / `cancelled` (and manual) entries never change adherence.
  */
class AdherenceProperties extends munit.ScalaCheckSuite:

  private val genStatus: Gen[OccurrenceStatus] =
    Gen.oneOf(OccurrenceStatus.values.toList)

  /** A status-consistent entry: taken rows carry an `effective_at` near the scheduled time (possibly late, possibly
    * corrected, possibly manual), skipped/missed rows carry a resolution instant.
    */
  private val genEntry: Gen[AdherenceInput] =
    for
      scheduledFor <- FsmGens.genInstant
      graceSeconds <- Gen.choose(300L, 14400L)
      status <- genStatus
      delta <- Gen.choose(-7200L, 172800L)
      manual <- Gen.frequency(1 -> true, 5 -> false)
      corrected <- Gen.frequency(1 -> true, 5 -> false)
    yield AdherenceInput(
      scheduledFor = scheduledFor,
      status = status,
      effectiveAt =
        if status == OccurrenceStatus.Taken then Some(scheduledFor.plusSeconds(delta)) else None,
      resolvedAt =
        if status == OccurrenceStatus.Skipped || status == OccurrenceStatus.Missed
        then Some(scheduledFor.plusSeconds(delta))
        else None,
      dueWindowEnd = scheduledFor.plusSeconds(graceSeconds),
      manual = manual,
      corrected = corrected
    )

  private val genEntries: Gen[List[AdherenceInput]] =
    Gen.choose(0, 30).flatMap(Gen.listOfN(_, genEntry))

  private val genExcludedEntry: Gen[AdherenceInput] =
    for
      scheduledFor <- FsmGens.genInstant
      status <- Gen.oneOf(OccurrenceStatus.Unknown, OccurrenceStatus.Cancelled)
    yield AdherenceInput(scheduledFor, status, None, None, scheduledFor.plusSeconds(3600), manual = false, corrected = false)

  property("adherence and on-time rate stay within [0, 1] whenever defined"):
    forAll(genEntries) { entries =>
      Adherence.adherence(entries).foreach(a => assert(a >= 0.0 && a <= 1.0, s"adherence $a out of range"))
      Adherence.onTimeRate(entries).foreach(r => assert(r >= 0.0 && r <= 1.0, s"on-time rate $r out of range"))
    }

  property("counts sum to resolved: every included entry lands in exactly one bucket"):
    forAll(genEntries) { entries =>
      val counts = Adherence.counts(entries)
      assertEquals(counts.taken + counts.skipped + counts.missed, counts.resolved)
      assertEquals(counts.resolved, entries.count(entry => Adherence.classify(entry).isDefined))
    }

  property("unknown and cancelled entries never change adherence"):
    forAll(genEntries, Gen.listOf(genExcludedEntry)) { (entries, extras) =>
      assertEquals(Adherence.counts(entries ++ extras), Adherence.counts(entries))
      assertEquals(Adherence.adherence(entries ++ extras), Adherence.adherence(entries))
    }

  property("manual entries never change adherence"):
    forAll(genEntries, genEntry) { (entries, extra) =>
      val manual = extra.copy(manual = true)
      assertEquals(Adherence.counts(entries :+ manual), Adherence.counts(entries))
      assertEquals(Adherence.adherence(entries :+ manual), Adherence.adherence(entries))
    }
end AdherenceProperties
