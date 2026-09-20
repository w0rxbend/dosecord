package dosecord.core.domain

import dosecord.contracts.HhMm

import java.time.Instant
import java.time.ZoneId

import org.scalacheck.Gen

/** ScalaCheck generators for the M1.3 occurrence FSM. Generated occurrences satisfy the schema-CHECK invariants
  * documented on [[Occurrence]] by construction, so properties can assert that `Decide.decide` preserves them.
  */
object FsmGens:

  val utc: ZoneId = ZoneId.of("UTC")

  val genZone: Gen[ZoneId] =
    Gen.oneOf(utc, ZoneId.of("Europe/Kyiv"), ZoneId.of("America/New_York"))

  /** 2026-01-01T00:00:00Z .. 2027-01-01T00:00:00Z. */
  val genInstant: Gen[Instant] =
    Gen.choose(1767225600L, 1798761600L).map(Instant.ofEpochSecond)

  /** A policy whose derived window is ordered (`dueWindowStart <= missDeadline`: the schema's `occ_window_order`).
    */
  val genPolicy: Gen[ReminderPolicy] =
    for
      missAfter <- Gen.choose(30, 2880)
      offset <- Gen.choose(-120, Math.min(1440, missAfter))
      repeatEvery <- Gen.choose(1, 60)
      maxReminders <- Gen.choose(1, 5)
      onTimeGrace <- Gen.choose(5, 240)
      maxSnoozes <- Gen.choose(0, 4)
      missAfterSnooze <- Gen.choose(5, 120)
      maxLate <- Gen.choose(60, 2880)
      lateLogWindow <- Gen.choose(720, 10080)
      undoWindow <- Gen.choose(5, 30)
      mode <- Gen.oneOf(QuietHoursMode.values.toList)
    yield ReminderPolicy(
      initialOffsetMinutes = offset,
      repeatEveryMinutes = repeatEvery,
      maxReminders = maxReminders,
      missAfterMinutes = missAfter,
      onTimeGraceMinutes = onTimeGrace,
      snoozeOptionsMinutes = List(10, 30, 60),
      maxSnoozes = maxSnoozes,
      missAfterSnoozeMinutes = missAfterSnooze,
      maxLateMinutes = maxLate,
      lateLogWindowMinutes = lateLogWindow,
      undoWindowMinutes = undoWindow,
      quietHoursMode = mode,
      discreet = false
    )

  def genOccurrence(policy: ReminderPolicy, scheduledFor: Instant): Gen[Occurrence] =
    val base = Occurrence.scheduled(scheduledFor, policy)
    Gen.oneOf(OccurrenceStatus.values.toList).flatMap {
      case OccurrenceStatus.Pending => Gen.const(base)
      case OccurrenceStatus.Due     =>
        Gen.choose(1, policy.maxReminders).map { seq =>
          base.copy(
            status = OccurrenceStatus.Due,
            reminderSeq = seq,
            lastRemindedAt = Some(scheduledFor),
            nextActionAt = Some(base.dueWindowStart)
          )
        }
      case OccurrenceStatus.Snoozed =>
        Gen.choose(300L, 3600L).map { delta =>
          val until = base.dueWindowStart.plusSeconds(delta)
          base.copy(
            status = OccurrenceStatus.Snoozed,
            reminderSeq = 1,
            snoozeCount = 1,
            snoozedUntil = Some(until),
            lastRemindedAt = Some(scheduledFor),
            nextActionAt = Some(until)
          )
        }
      case OccurrenceStatus.Taken     =>
        Gen.const(
          base.copy(
            status = OccurrenceStatus.Taken,
            takenAt = Some(scheduledFor),
            effectiveAt = Some(scheduledFor),
            nextActionAt = None
          )
        )
      case OccurrenceStatus.Skipped   =>
        Gen.const(base.copy(status = OccurrenceStatus.Skipped, skippedAt = Some(scheduledFor), nextActionAt = None))
      case OccurrenceStatus.Missed    =>
        Gen.const(base.copy(status = OccurrenceStatus.Missed, missedAt = Some(base.missDeadline), nextActionAt = None))
      case OccurrenceStatus.Unknown   =>
        Gen.oneOf(UnknownReason.values.toList).map { reason =>
          base.copy(status = OccurrenceStatus.Unknown, unknownReason = Some(reason), nextActionAt = None)
        }
      case OccurrenceStatus.Cancelled =>
        Gen.const(base.copy(status = OccurrenceStatus.Cancelled, nextActionAt = None))
    }

  def genOpenOccurrence(policy: ReminderPolicy, scheduledFor: Instant): Gen[Occurrence] =
    genOccurrence(policy, scheduledFor).retryUntil(_.status.isOpen)

  val genEvent: Gen[OccurrenceEvent] =
    Gen.oneOf(
      Gen.const(OccurrenceEvent.Tick),
      Gen.const(OccurrenceEvent.Taken),
      Gen.option(Gen.oneOf(SkipReason.values.toList)).map(OccurrenceEvent.Skipped(_)),
      Gen.choose(1, 240).map(OccurrenceEvent.Snoozed(_)),
      genInstant.map(OccurrenceEvent.Corrected(_)),
      Gen.const(OccurrenceEvent.Undo),
      Gen.alphaNumStr.suchThat(_.nonEmpty).map(OccurrenceEvent.NoteAdded(_))
    )

  def genContext(event: OccurrenceEvent, scheduledFor: Instant): Gen[DecideContext] =
    for
      delivered <- Gen.oneOf(true, false)
      healthyDelta <- Gen.choose(-7200L, 7200L)
      nextOcc <- Gen.option(Gen.choose(3600L, 48L * 3600L).map(d => scheduledFor.plusSeconds(d)))
      lastAction <- Gen.option(
        for
          kind <- Gen.oneOf(copy.DoseActionKind.Taken, copy.DoseActionKind.Skipped, copy.DoseActionKind.Snoozed)
          seq <- Gen.choose(1, 5)
          at <- Gen.choose(-3600L, 3600L).map(d => scheduledFor.plusSeconds(d))
        yield LastUserAction(kind, seq, at)
      )
    yield DecideContext(
      event,
      delivered = delivered,
      lastHealthyTick = scheduledFor.plusSeconds(healthyDelta),
      nextOccurrenceScheduledFor = nextOcc,
      lastUndoableAction = lastAction
    )

  val genQuiet: Gen[QuietHoursContext] =
    for
      zone <- genZone
      hours <- Gen.option(
        for
          start <- ScheduleGens.genHhMm
          end <- ScheduleGens.genHhMm.suchThat(_ != start)
        yield QuietHours(start, end)
      )
    yield QuietHoursContext(hours, zone)

  /** An instant inside active quiet hours: a random local date in the zone plus a wall minute inside the interval.
    */
  def genNowInsideQuiet(zone: ZoneId, quiet: QuietHours): Gen[Instant] =
    val from = quiet.start.hour * 60 + quiet.start.minute
    val to = quiet.end.hour * 60 + quiet.end.minute
    val length = if from < to then to - from else 24 * 60 - from + to
    for
      date <- Gen.choose(0L, 400L).map(java.time.LocalDate.of(2026, 1, 1).plusDays)
      offset <- Gen.choose(0, length - 1)
    yield
      val minute = (from + offset) % (24 * 60)
      Dst.resolveLocal(date, java.time.LocalTime.of(minute / 60, minute % 60), zone)._1

  def quietZone(start: String, end: String, zone: ZoneId = utc): QuietHoursContext =
    QuietHoursContext(Some(QuietHours(HhMm.unsafe(start), HhMm.unsafe(end))), zone)

  /** The schema-CHECK invariants of `dose_occurrences`, checked on the pure row. */
  def holdsSchemaInvariants(occ: Occurrence): Boolean =
    occ.status.isOpen == occ.nextActionAt.isDefined &&
      (occ.status == OccurrenceStatus.Taken) == occ.takenAt.isDefined &&
      (occ.status == OccurrenceStatus.Skipped) == occ.skippedAt.isDefined &&
      (occ.status != OccurrenceStatus.Snoozed || occ.snoozedUntil.isDefined) &&
      (occ.status != OccurrenceStatus.Unknown || occ.unknownReason.isDefined) &&
      !occ.dueWindowStart.isAfter(occ.missDeadline)
end FsmGens
