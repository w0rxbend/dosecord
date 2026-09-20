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
end DecideProperties
