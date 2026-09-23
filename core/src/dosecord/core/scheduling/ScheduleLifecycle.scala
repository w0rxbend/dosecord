package dosecord.core.scheduling

import dosecord.contracts.AccountId
import dosecord.contracts.Actor as ContractsActor
import dosecord.contracts.Event
import dosecord.core.domain.Actor
import dosecord.core.domain.CancelReason
import dosecord.core.domain.DoseSnapshot
import dosecord.core.domain.Evaluator
import dosecord.core.domain.OccurrenceStatus
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.copy.DoseActionKind
import dosecord.core.ports.Clock
import dosecord.core.ports.NewDomainEvent
import dosecord.core.ports.NewDoseAction
import dosecord.core.ports.NewMedication
import dosecord.core.ports.NewSchedule
import dosecord.core.ports.NewScheduleRevision
import dosecord.core.ports.ScheduleStatus
import dosecord.core.ports.StoredOccurrence
import dosecord.core.ports.StoredSchedule
import dosecord.core.ports.StoredScheduleRevision
import dosecord.core.ports.Tx
import dosecord.core.ports.UnitOfWork

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What a schedule create needs (ROADMAP M1.5; the M1.9 wizard builds these). Only `FixedTimes` and `AsNeeded` pass
  * validation until M7 (ROADMAP M1.1).
  */
final case class CreateSchedule(
    accountId: UUID,
    medicationName: String,
    rule: Rule,
    zone: ZoneId,
    doseAmount: Option[BigDecimal] = None,
    doseUnit: Option[String] = None,
    instructions: Option[String] = None,
    policy: ReminderPolicy = ReminderPolicy.Default,
    tzFollowsUser: Boolean = true
)

/** The result of one revision application. `keptForQuestion` holds the kept rows of DESIGN.md section 7.1 that the M3.2
  * wizard must ask about ("Keep tonight's dose at 21:00 New York?"): old-revision pending rows that fire before the new
  * revision's first candidate on a local date preceding the new effective date (the eastward move), and pending rows
  * before the cutover that own a `(local_date, slot_key)` the new revision also produces — through the live-slot index
  * they suppress that candidate, so the dose fires once, at the kept row's instant. They stay live under the old
  * revision.
  */
final case class RevisionOutcome(
    scheduleId: UUID,
    medicationId: UUID,
    revision: Int,
    effectiveFrom: Instant,
    materialized: Int,
    cancelled: List[UUID],
    keptForQuestion: List[StoredOccurrence]
)

private final case class RevisionPlan(
    rule: Rule,
    zone: ZoneId,
    policy: ReminderPolicy,
    createdBy: String,
    reason: String,
    cancelReason: CancelReason,
    status: Option[ScheduleStatus],
    effectiveFrom: Option[Instant]
)

/** The schedule revision lifecycle of ADR-004 / DESIGN.md section 7.1 (ROADMAP M1.5): create, edit, pause, resume,
  * archive and timezone change as append-only `schedule_revisions`. Every op is one transaction that locks the schedule
  * row and its open occurrences `FOR UPDATE`, appends the revision, reconciles the superseded revision's pending rows
  * (`cancelled` + a `dose_actions` row each) and materialises the new revision over the 48 h horizon. `effective_from`
  * defaults to the next local midnight when any of today's slots is already resolved or due, else "now";
  * pause/resume/archive are always effective immediately.
  */
final class ScheduleLifecycle(uow: UnitOfWork, clock: Clock):

  /** Creates the medication, the schedule and revision 1, materialises the 48 h horizon and appends
    * `dosecord.medication.schedule_created.v1` — all in one transaction (R30).
    */
  def create(cmd: CreateSchedule): Either[List[String], RevisionOutcome] =
    val errors = ruleErrors(cmd.rule)
    if errors.nonEmpty then Left(errors)
    else Right(uow.transaction(tx => createInTx(tx, cmd, clock.now())))

  /** The transaction half of [[create]] (M1.9: the add-medication wizard completes inside the mediator's per-event
    * transaction, so the rows commit or roll back with the conversation state).
    */
  def createInTx(tx: Tx, cmd: CreateSchedule, now: Instant): RevisionOutcome =
    val medicationId = UUID.randomUUID()
    tx.medications.insert(
      NewMedication(
        medicationId,
        cmd.accountId,
        cmd.medicationName,
        cmd.doseAmount,
        cmd.doseUnit,
        cmd.instructions
      ),
      now
    )
    val scheduleId = UUID.randomUUID()
    val startDate = now.atZone(cmd.zone).toLocalDate
    val kind = Rule.kindTag(cmd.rule)
    tx.schedules.insert(
      NewSchedule(scheduleId, medicationId, cmd.accountId, kind, cmd.zone, cmd.tzFollowsUser, startDate),
      now
    )
    val snapshot = DoseSnapshot(cmd.medicationName, cmd.doseAmount, cmd.doseUnit, cmd.instructions)
    val revision = NewScheduleRevision(
      UUID.randomUUID(),
      scheduleId,
      revision = 1,
      effectiveFrom = now,
      tz = cmd.zone,
      rule = cmd.rule,
      policy = cmd.policy,
      doseSnapshot = snapshot,
      createdBy = "user",
      reason = Some("create")
    )
    tx.revisions.append(revision, now)
    val storedSchedule =
      StoredSchedule(
        scheduleId,
        medicationId,
        cmd.accountId,
        kind,
        ScheduleStatus.Active,
        1,
        cmd.zone,
        cmd.tzFollowsUser,
        startDate,
        None,
        None
      )
    val horizonEnd = now.plus(Evaluator.MaterialisationHorizon)
    val inserted =
      Materialiser.materializeSchedule(tx, storedSchedule, stored(revision, now), now, horizonEnd, now).inserted
    tx.schedules.advanceMaterializedThrough(scheduleId, horizonEnd, now)
    appendScheduleCreated(tx, cmd.accountId, medicationId, scheduleId, cmd.zone, now)
    RevisionOutcome(scheduleId, medicationId, revision = 1, effectiveFrom = now, inserted, Nil, Nil)

  /** A rule/policy edit in the schedule's current zone (the M3.2 edit wizard's data side). */
  def edit(
      scheduleId: UUID,
      rule: Rule,
      policy: Option[ReminderPolicy] = None,
      effectiveFrom: Option[Instant] = None
  ): RevisionOutcome =
    uow.transaction(tx => editInTx(tx, scheduleId, rule, policy, effectiveFrom, clock.now()))

  /** The transaction half of [[edit]] (M1.9's find-or-create update path). */
  def editInTx(
      tx: Tx,
      scheduleId: UUID,
      rule: Rule,
      policy: Option[ReminderPolicy] = None,
      effectiveFrom: Option[Instant],
      now: Instant
  ): RevisionOutcome =
    val errors = ruleErrors(rule)
    require(errors.isEmpty, errors.mkString("; "))
    plannedInTx(tx, scheduleId, now) { (_, schedule, latest, _) =>
      requireActive(schedule)
      RevisionPlan(
        rule,
        schedule.tz,
        policy.getOrElse(latest.policy),
        "user",
        "edit",
        CancelReason.Superseded,
        None,
        effectiveFrom
      )
    }

  /** Pause is a revision too (`Paused` rule, ADR-004), effective now: future pending rows are cancelled `paused`. */
  def pause(scheduleId: UUID): RevisionOutcome =
    uow.transaction(tx => pauseInTx(tx, scheduleId, clock.now()))

  /** The transaction half of [[pause]] (M1.9's menu toggle). */
  def pauseInTx(tx: Tx, scheduleId: UUID, now: Instant): RevisionOutcome =
    plannedInTx(tx, scheduleId, now) { (_, schedule, latest, _) =>
      requireActive(schedule)
      RevisionPlan(
        Rule.Paused,
        schedule.tz,
        latest.policy,
        "user",
        "pause",
        CancelReason.Paused,
        Some(ScheduleStatus.Paused),
        Some(now)
      )
    }

  /** Resume copies the rule and policy of the revision before the `Paused` one, in the schedule's current zone (a user
    * timezone change on a paused schedule moves the row, and resume picks the new zone up here).
    */
  def resume(scheduleId: UUID): RevisionOutcome =
    uow.transaction(tx => resumeInTx(tx, scheduleId, clock.now()))

  /** The transaction half of [[resume]] (M1.9's menu toggle). */
  def resumeInTx(tx: Tx, scheduleId: UUID, now: Instant): RevisionOutcome =
    plannedInTx(tx, scheduleId, now) { (tx, schedule, latest, _) =>
      require(
        schedule.status == ScheduleStatus.Paused,
        s"schedule $scheduleId is not paused (status ${schedule.status.dbValue})"
      )
      val prior = latest.rule match
        case Rule.Paused =>
          tx.revisions
            .get(scheduleId, latest.revision - 1)
            .getOrElse(throw new IllegalStateException(s"schedule $scheduleId has no rule before its pause"))
        case _ => latest
      RevisionPlan(
        prior.rule,
        schedule.tz,
        prior.policy,
        "user",
        "resume",
        CancelReason.Superseded,
        Some(ScheduleStatus.Active),
        Some(now)
      )
    }

  /** Archive is a revision that cancels future pending rows `archived` and materialises nothing more. */
  def archive(scheduleId: UUID): RevisionOutcome =
    uow.transaction(tx => archiveInTx(tx, scheduleId, clock.now()))

  /** The transaction half of [[archive]] (M1.9's menu entry). */
  def archiveInTx(tx: Tx, scheduleId: UUID, now: Instant): RevisionOutcome =
    plannedInTx(tx, scheduleId, now) { (_, schedule, latest, _) =>
      require(schedule.status != ScheduleStatus.Archived, s"schedule $scheduleId is already archived")
      RevisionPlan(
        latest.rule,
        schedule.tz,
        latest.policy,
        "user",
        "archive",
        CancelReason.Archived,
        Some(ScheduleStatus.Archived),
        Some(now)
      )
    }

  /** A timezone change as a revision (R10): same rule and policy, new zone. With the default `effective_from` the
    * next-local-midnight rule applies; rows the new revision cannot reach before it governs are kept and flagged
    * (`RevisionOutcome.keptForQuestion`, the eastward-move case of DESIGN.md section 7.1).
    */
  def changeTimezone(scheduleId: UUID, newZone: ZoneId, effectiveFrom: Option[Instant] = None): RevisionOutcome =
    uow.transaction(tx => changeTimezoneInTx(tx, scheduleId, newZone, effectiveFrom, clock.now()))

  /** The transaction half of [[changeTimezone]]. */
  def changeTimezoneInTx(
      tx: Tx,
      scheduleId: UUID,
      newZone: ZoneId,
      effectiveFrom: Option[Instant],
      now: Instant
  ): RevisionOutcome =
    plannedInTx(tx, scheduleId, now) { (_, schedule, latest, _) =>
      requireActive(schedule)
      RevisionPlan(
        latest.rule,
        newZone,
        latest.policy,
        "user",
        "tz_change",
        CancelReason.Superseded,
        None,
        effectiveFrom
      )
    }

  /** The data side of Account -> Timezone (R10): re-revisions the account's active `tz_follows_user` schedules into the
    * new zone; non-following schedules are untouched. Paused following schedules get the row update only — their
    * `Paused` revision materialises nothing and resume picks the new zone up.
    */
  def rezoneFollowingSchedules(accountId: UUID, newZone: ZoneId): List[RevisionOutcome] =
    uow.transaction(tx => rezoneFollowingSchedulesInTx(tx, accountId, newZone, clock.now()))

  /** The transaction half of [[rezoneFollowingSchedules]] (M1.9's Account -> Timezone completes inside the mediator's
    * per-event transaction).
    */
  def rezoneFollowingSchedulesInTx(tx: Tx, accountId: UUID, newZone: ZoneId, now: Instant): List[RevisionOutcome] =
    val following = tx.schedules.listFollowingForTzChange(accountId)
    val (active, paused) = following.partition(_.status == ScheduleStatus.Active)
    paused.filter(_.tz != newZone).foreach { schedule =>
      tx.schedules.setTimezone(schedule.id, newZone, now)
    }
    active.filter(_.tz != newZone).map(schedule => changeTimezoneInTx(tx, schedule.id, newZone, None, now))

  private def plannedInTx(
      tx: Tx,
      scheduleId: UUID,
      now: Instant
  )(f: (Tx, StoredSchedule, StoredScheduleRevision, Instant) => RevisionPlan): RevisionOutcome =
    val schedule = tx.schedules
      .getForUpdate(scheduleId)
      .getOrElse(throw new NoSuchElementException(s"unknown schedule $scheduleId"))
    val latest = tx.revisions
      .latest(scheduleId)
      .getOrElse(throw new IllegalStateException(s"schedule $scheduleId has no revisions"))
    applyPlan(tx, schedule, latest, f(tx, schedule, latest, now), now)

  private def applyPlan(
      tx: Tx,
      schedule: StoredSchedule,
      latest: StoredScheduleRevision,
      plan: RevisionPlan,
      now: Instant
  ): RevisionOutcome =
    val openRows = tx.occurrences.lockOpenRows(schedule.id)
    val effective = plan.effectiveFrom.getOrElse(defaultEffectiveFrom(tx, schedule, plan.zone, now))
    val newRevision = latest.revision + 1
    val revision = NewScheduleRevision(
      UUID.randomUUID(),
      schedule.id,
      newRevision,
      effectiveFrom = effective,
      tz = plan.zone,
      rule = plan.rule,
      policy = plan.policy,
      doseSnapshot = latest.doseSnapshot,
      createdBy = plan.createdBy,
      reason = Some(plan.reason)
    )
    tx.revisions.append(revision, now)
    tx.schedules.setCurrentRevision(schedule.id, newRevision, now)
    if plan.zone != schedule.tz then tx.schedules.setTimezone(schedule.id, plan.zone, now)
    plan.status.foreach(status => tx.schedules.setStatus(schedule.id, status, now))

    val updatedSchedule = schedule.copy(currentRevision = newRevision, tz = plan.zone)
    val storedRevision = stored(revision, now)
    val horizonEnd = now.plus(Evaluator.MaterialisationHorizon)
    val newCandidates =
      Evaluator.occurrences(Materialiser.toEvaluator(updatedSchedule, storedRevision), now, horizonEnd)
    val firstCandidate = newCandidates.headOption.map(_.scheduledFor)

    // Reconciliation (DESIGN.md section 7.1): cancel the superseded revision's pending rows at or after
    // effective_from, except the kept eastward rows (old local date precedes the new effective date and the row
    // fires before the new revision's first candidate). Due/snoozed rows and earlier revisions' rows are untouched.
    val effectiveLocalDate = effective.atZone(plan.zone).toLocalDate
    val superseded = openRows.filter(row =>
      row.status == OccurrenceStatus.Pending &&
        row.revision.contains(latest.revision) &&
        !row.scheduledFor.isBefore(effective)
    )
    val (kept, cancelled) = superseded.partition(row =>
      row.localDate.isBefore(effectiveLocalDate) &&
        firstCandidate.exists(candidate => row.scheduledFor.isBefore(candidate))
    )
    if cancelled.nonEmpty then
      tx.occurrences.cancel(cancelled.map(_.id), plan.cancelReason, now)
      val correlationId = UUID.randomUUID().toString
      cancelled.foreach { row =>
        tx.doseActions.append(
          NewDoseAction(
            id = UUID.randomUUID(),
            occurrenceId = row.id,
            accountId = row.accountId,
            action = DoseActionKind.Cancelled,
            actor = Actor.System,
            occurredAt = now,
            priorStatus = row.status,
            newStatus = OccurrenceStatus.Cancelled,
            correlationId = correlationId,
            metadata = s"""{"cancel_reason":"${plan.cancelReason.dbValue}","superseded_by_revision":$newRevision}"""
          )
        )
      }

    // A pending row before the cutover is never cancelled, but when it owns a `(local_date, slot_key)` the new
    // revision also produces it suppresses that candidate through the cross-revision live-slot index (ADR-004: the
    // index, not the reconciler, arbitrates — the dose fires once, at the kept row's instant). It is a kept row the
    // M3.2 question must name (DESIGN.md section 7.1: "kept, not cancelled, and the wizard asks").
    val candidateKeys = newCandidates.map(candidate => (candidate.localDate, candidate.slotKey)).toSet
    val suppressed = openRows.filter(row =>
      row.status == OccurrenceStatus.Pending &&
        row.revision.contains(latest.revision) &&
        row.scheduledFor.isBefore(effective) &&
        candidateKeys.contains(row.localDate -> row.slotKey)
    )
    val keptForQuestion = kept ++ suppressed

    val resultingStatus = plan.status.getOrElse(schedule.status)
    val inserted =
      if resultingStatus != ScheduleStatus.Active then 0
      else
        val n = Materialiser.materializeSchedule(tx, updatedSchedule, storedRevision, now, horizonEnd, now).inserted
        tx.schedules.advanceMaterializedThrough(schedule.id, horizonEnd, now)
        n
    RevisionOutcome(
      schedule.id,
      schedule.medicationId,
      newRevision,
      effective,
      inserted,
      cancelled.map(_.id),
      keptForQuestion
    )

  /** The next-local-midnight rule (DESIGN.md section 7.1): when any of today's slots under the old revision is already
    * resolved or due, the new plan starts tomorrow (in the new zone); otherwise it starts now.
    */
  private def defaultEffectiveFrom(tx: Tx, schedule: StoredSchedule, newZone: ZoneId, now: Instant): Instant =
    val today = now.atZone(schedule.tz).toLocalDate
    if tx.occurrences.hasResolvedOrDueOn(schedule.id, today, now) then
      now.atZone(newZone).toLocalDate.plusDays(1).atStartOfDay(newZone).toInstant
    else now

  private def appendScheduleCreated(
      tx: Tx,
      accountId: UUID,
      medicationId: UUID,
      scheduleId: UUID,
      zone: ZoneId,
      now: Instant
  ): Unit =
    val account = AccountId(accountId)
    val event = Event.ScheduleCreated(account, medicationId, scheduleId, zone.getId)
    val actor = ContractsActor("dosecord", accountId.toString, None, Some(account))
    val correlationId = UUID.randomUUID().toString
    val envelope = Event.toEnvelopeJson(event, actor, now, Some(correlationId), None)
    tx.domainEvents.append(
      NewDomainEvent(
        id = envelope.id.uuid,
        eventType = Event.ScheduleCreatedType,
        source = Event.sourceOf(event).value,
        subject = Some(actor.subject),
        accountId = Some(accountId),
        correlationId = Some(correlationId),
        causationId = None,
        actorJson = envelope.actorJson,
        occurredAt = now,
        dataJson = envelope.envelopeJson
      )
    )

  private def stored(revision: NewScheduleRevision, now: Instant): StoredScheduleRevision =
    StoredScheduleRevision(
      revision.id,
      revision.scheduleId,
      revision.revision,
      revision.effectiveFrom,
      revision.tz,
      revision.rule,
      revision.policy,
      revision.doseSnapshot,
      revision.createdBy,
      revision.reason,
      createdAt = now
    )

  private def requireActive(schedule: StoredSchedule): Unit =
    require(
      schedule.status == ScheduleStatus.Active,
      s"schedule ${schedule.id} is not active (status ${schedule.status.dbValue})"
    )

  private def ruleErrors(rule: Rule): List[String] =
    Rule.validate(rule) ++
      (if Rule.isSupportedInMvp(rule) then Nil
       else List(s"rule kind ${Rule.kindTag(rule)} is not supported until M7"))
