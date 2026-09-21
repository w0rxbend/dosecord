package dosecord.core.domain

import java.time.Instant

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** ROADMAP M1.3 properties over `Decide.decide` and `Decide.availableSnoozeOptions`.
  */
class DecideProperties extends munit.ScalaCheckSuite:

  import FsmGens.*

  private def tickContext(delivered: Boolean, lastHealthyTick: Instant): DecideContext =
    DecideContext(OccurrenceEvent.Tick, delivered = delivered, lastHealthyTick = lastHealthyTick)

  private val noQuiet: QuietHoursContext = QuietHoursContext.none(utc)

  property("schema invariants hold after any decide"):
    val genInput =
      for
        policy <- genPolicy
        scheduledFor <- genInstant
        occ <- genOccurrence(policy, scheduledFor)
        event <- genEvent
        quiet <- genQuiet
        ctx <- genContext(event, scheduledFor)
        delta <- Gen.choose(-172800L, 259200L)
      yield (policy, occ, event, quiet, ctx, scheduledFor.plusSeconds(delta))
    forAll(genInput) { (policy, occ, event, quiet, ctx, now) =>
      assert(holdsSchemaInvariants(occ), s"generated occurrence violates the invariants: $occ")
      val result = Decide.decide(occ, policy, quiet, now, ctx)
      assert(
        holdsSchemaInvariants(result.row),
        s"invariants broken by (${occ.status}, $event) at $now: ${result.row}"
      )
    }

  property("unknown is never produced inside quiet hours"):
    val genScenario =
      for
        zone <- genZone
        start <- ScheduleGens.genHhMm
        end <- ScheduleGens.genHhMm.suchThat(_ != start)
        quiet = QuietHours(start, end)
        now <- genNowInsideQuiet(zone, quiet)
        policy <- genPolicy
        secondsBack <- Gen.choose(0L, 72L * 3600L)
        scheduledFor = now.minusSeconds(secondsBack)
        occ <- genOpenOccurrence(policy, scheduledFor)
        healthyDelta <- Gen.choose(-7200L, 7200L)
      yield (QuietHoursContext(Some(quiet), zone), policy, occ, now, scheduledFor.plusSeconds(healthyDelta))
    forAll(genScenario) { (quiet, policy, occ, now, lastHealthyTick) =>
      assert(quiet.contains(now), s"generator drifted: $now is not inside quiet hours")
      val result = Decide.decide(occ, policy, quiet, now, tickContext(delivered = false, lastHealthyTick))
      assert(
        result.row.status != OccurrenceStatus.Unknown,
        s"unknown produced inside quiet hours from (${occ.status}, tick) at $now"
      )
    }

  property("snooze options never pass the next occurrence or scheduled_for + maxLate"):
    forAll(genPolicy, genInstant) { (policy, scheduledFor) =>
      forAll(genOpenOccurrence(policy, scheduledFor), genInstant, Gen.option(genInstant)) { (occ, now, nextOcc) =>
        val options = Decide.availableSnoozeOptions(occ, policy, now, nextOcc, None)
        val base = if now.isBefore(occ.dueWindowStart) then occ.dueWindowStart else now
        val lateBound = occ.scheduledFor.plusSeconds(policy.maxLateMinutes.toLong * 60L)
        options.foreach { minutes =>
          val until = base.plusSeconds(minutes.toLong * 60L)
          nextOcc.foreach { next =>
            assert(!until.isAfter(next.minusSeconds(60L)), s"option $minutes passes the next occurrence $next")
          }
          assert(!until.isAfter(lateBound), s"option $minutes passes scheduled_for + maxLate")
        }
      }
    }

  property("reminder_seq never exceeds maxReminders under any tick sequence"):
    forAll(genPolicy, genInstant, Gen.choose(1, 30), Gen.oneOf(true, false)) {
      (policy, scheduledFor, steps, delivered) =>
        var occ = Occurrence.scheduled(scheduledFor, policy)
        var now = occ.dueWindowStart
        var remaining = steps
        var ok = true
        while remaining > 0 && occ.status.isOpen && ok do
          val result = Decide.decide(
            occ,
            policy,
            QuietHoursContext.none(utc),
            now,
            tickContext(delivered = delivered, scheduledFor)
          )
          occ = result.row
          if occ.reminderSeq > policy.maxReminders then ok = false
          now = occ.nextActionAt.getOrElse(now.plusSeconds(3600L))
          remaining -= 1
        assert(ok, s"reminder_seq ${occ.reminderSeq} exceeded maxReminders ${policy.maxReminders}")
    }

  property("undo within the window restores the prior projection modulo the ADR-012 extensions"):
    val genInput =
      for
        policy <- genPolicy
        scheduledFor <- genInstant
        seq <- Gen.choose(1, policy.maxReminders)
        takenDeltaSeconds <- Gen.choose(0L, 3600L) // within every generated late-log window (>= 12 h)
        undoDeltaMinutes <- Gen.choose(0, policy.undoWindowMinutes)
        resolution <- Gen.oneOf(OccurrenceEvent.Taken, OccurrenceEvent.Skipped(None))
      yield (policy, scheduledFor, seq, takenDeltaSeconds, undoDeltaMinutes, resolution)
    forAll(genInput) { (policy, scheduledFor, seq, takenDeltaSeconds, undoDeltaMinutes, resolution) =>
      val occ = Occurrence.scheduled(scheduledFor, policy).copy(
        status = OccurrenceStatus.Due,
        reminderSeq = seq,
        lastRemindedAt = Some(scheduledFor),
        nextActionAt = Some(scheduledFor)
      )
      val resolvedAt = scheduledFor.plusSeconds(takenDeltaSeconds)
      val resolved = Decide.decide(occ, policy, noQuiet, resolvedAt, DecideContext(resolution))
      val kind =
        resolution match
          case OccurrenceEvent.Taken      => copy.DoseActionKind.Taken
          case OccurrenceEvent.Skipped(_) => copy.DoseActionKind.Skipped
          case _                          => copy.DoseActionKind.Taken // unreachable: the generator only emits the two above
      val undoAt = resolvedAt.plusSeconds(undoDeltaMinutes.toLong * 60L)
      val undone = Decide.decide(
        resolved.row,
        policy,
        noQuiet,
        undoAt,
        DecideContext(OccurrenceEvent.Undo, lastUndoableAction = Some(LastUserAction(kind, 7, resolvedAt)))
      )
      val row = undone.row
      // The prior projection is restored: resolution fields cleared, seq and windows as before the action...
      assertEquals(row.status, OccurrenceStatus.Due)
      assertEquals(row.takenAt, None)
      assertEquals(row.skippedAt, None)
      assertEquals(row.effectiveAt, None)
      assertEquals(row.snoozedUntil, None)
      assertEquals(row.reminderSeq, occ.reminderSeq)
      assertEquals(row.dueWindowStart, occ.dueWindowStart)
      assertEquals(row.dueWindowEnd, occ.dueWindowEnd)
      assertEquals(row.scheduledFor, occ.scheduledFor)
      assertEquals(row.snoozeCount, occ.snoozeCount)
      // ...modulo the documented extensions: miss_deadline only grows, the reminder is re-armed, epochs accrue.
      assert(!row.missDeadline.isBefore(occ.missDeadline), "undo must never shrink the miss deadline")
      assertEquals(row.nextActionAt, Some(undoAt.plusSeconds(policy.repeatEveryMinutes.toLong * 60L)))
      assertEquals(row.epoch, occ.epoch + 2)
      assertEquals(undone.action.map(_.action), Some(copy.DoseActionKind.Undone))
      assertEquals(undone.action.flatMap(_.undoesSeq), Some(7))
    }
end DecideProperties
