package dosegram.api

import dosegram.core.domain.Evaluator
import dosegram.core.domain.ScheduleRevision
import dosegram.core.ports.Clock
import dosegram.core.ports.MedicationEdit
import dosegram.core.ports.MedicationStatus
import dosegram.core.ports.NewMedication
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.StoredMedication
import dosegram.core.ports.StoredSchedule
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import dosegram.core.scheduling.AddSchedule
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/** The medication registry and its schedules for one authenticated account (ARCHITECTURE section 9.5). Every method
  * takes the account of the session; a row of another account is answered exactly like a row that does not exist
  * (API-11). Each write validates and checks the version first and writes last, so a refusal leaves nothing behind.
  */
final class Registry(uow: UnitOfWork, clock: Clock):
  import Registry.*

  private val lifecycle = ScheduleLifecycle(uow, clock)

  // ---------- medications ----------

  def options: MedicationOptions = MedicationOptions(DoseUnits, RegistryLimits.published)

  def list(accountId: UUID, status: Option[MedicationState]): MedicationList =
    uow.transaction: tx =>
      val summaries = tx.medications.listAllForAccount(accountId).map(Rendering.medicationSummary(tx, _))
      MedicationList(summaries.filter(summary => status.forall(_ == summary.status)))

  def get(accountId: UUID, id: UUID): Either[ApiFailure, Medication] =
    uow.transaction(tx => ownedMedication(tx, accountId, id).map(Rendering.medication(tx, _)))

  def create(accountId: UUID, input: MedicationInput): Either[ApiFailure, Medication] =
    uow.transaction: tx =>
      for
        fields <- MedicationFields.validate(input)
        _ <- nameIsFree(tx, accountId, fields.name, except = None)
      yield
        val id = UUID.randomUUID()
        val row = NewMedication(id, accountId, fields.name, fields.doseAmount, fields.doseUnit, fields.instructions)
        tx.medications.insert(row, clock.now())
        Rendering.medication(tx, tx.medications.get(id).get)

  /** Replaces the editable set. Reminders carry the dose as a snapshot per schedule revision, so an edit that changes
    * the snapshot also appends a revision to each active schedule.
    */
  def update(accountId: UUID, id: UUID, version: Long, input: MedicationInput): Either[ApiFailure, Medication] =
    uow.transaction: tx =>
      for
        current <- ownedMedication(tx, accountId, id)
        _ <- currentVersion(tx, current, version)
        fields <- MedicationFields.validate(input)
        _ <- nameIsFree(tx, accountId, fields.name, except = Some(id))
        written <- replace(
          tx,
          current,
          _.copy(
            name = fields.name,
            doseAmount = fields.doseAmount,
            doseUnit = fields.doseUnit,
            instructions = fields.instructions
          )
        )
      yield
        refreshSnapshots(tx, written)
        Rendering.medication(tx, written)

  def changeState(accountId: UUID, id: UUID, version: Long, change: MedicationChange): Either[ApiFailure, Medication] =
    uow.transaction: tx =>
      for
        current <- ownedMedication(tx, accountId, id)
        _ <- currentVersion(tx, current, version)
        target <- targetStatus(current.status, change)
        written <- if target == current.status then Right(current) else replace(tx, current, _.copy(status = target))
      yield
        moveSchedules(tx, written, target)
        Rendering.medication(tx, written)

  // ---------- schedules ----------

  def scheduleOptions(accountId: UUID): ScheduleOptions =
    val zone = uow.transaction(zoneOf(_, accountId))
    ScheduleOptions(
      SchedulePattern.values.toList,
      RegistryLimits.ScheduleTimesMax,
      PreviewDays,
      zone.getId,
      clock.now().atZone(zone).toLocalDate
    )

  /** The occurrences the engine would produce for the draft over the next seven days, from the same evaluator the
    * materialiser uses. Nothing is written.
    */
  def preview(accountId: UUID, draft: ScheduleDraft): Either[ApiFailure, SchedulePreview] =
    val zone = uow.transaction(zoneOf(_, accountId))
    val now = clock.now()
    val today = now.atZone(zone).toLocalDate
    ScheduleRules
      .validate(draft, today)
      .map: rule =>
        val revision = ScheduleRevision(
          rule,
          zone,
          effectiveFrom = draft.startDate.filter(_.isAfter(today)).getOrElse(today),
          effectiveUntil = draft.endDate.map(_.plusDays(1))
        )
        val candidates = Evaluator.occurrences(revision, now, now.plus(Duration.ofDays(PreviewDays.toLong)))
        val days = candidates
          .groupBy(_.localDate)
          .toList
          .sortBy(_._1.toEpochDay)
          .map: (date, onDay) =>
            val occurrences = onDay.map: candidate =>
              PreviewOccurrence(
                candidate.localTime.value,
                candidate.scheduledFor,
                ApiCopy.clockChangeNote(candidate, zone)
              )
            PreviewDay(date, ApiCopy.dayLabel(date), occurrences)
        SchedulePreview(ApiCopy.ruleSummary(rule), zone.getId, days)

  def schedules(accountId: UUID, medicationId: UUID): Either[ApiFailure, ScheduleList] =
    uow.transaction: tx =>
      ownedMedication(tx, accountId, medicationId).map: medication =>
        ScheduleList(tx.schedules.listForMedication(medication.id).map(Rendering.schedule(tx, _)))

  def schedule(accountId: UUID, id: UUID): Either[ApiFailure, Schedule] =
    uow.transaction(tx => ownedSchedule(tx, accountId, id).map(Rendering.schedule(tx, _)))

  /** One schedule per medication that has not ended: the chat menu and the reminder card assume it. */
  def createSchedule(accountId: UUID, medicationId: UUID, draft: ScheduleDraft): Either[ApiFailure, Schedule] =
    uow.transaction: tx =>
      for
        medication <- ownedMedication(tx, accountId, medicationId)
        _ <- Either.cond(
          medication.status != MedicationStatus.Archived,
          (),
          ApiFailure.invalid("medication", "archived")
        )
        _ <- Either.cond(
          liveSchedules(tx, medicationId).isEmpty,
          (),
          ApiFailure.invalid("medication", "schedule_exists")
        )
        rule <- ScheduleRules.validate(draft, clock.now().atZone(zoneOf(tx, accountId)).toLocalDate)
        plan = AddSchedule(
          medicationId,
          rule,
          zoneOf(tx, accountId),
          startDate = draft.startDate,
          endDate = draft.endDate
        )
        outcome <- lifecycle
          .addInTx(tx, accountId, plan, clock.now())
          .left
          .map(_ => ApiFailure.invalid("pattern", "unsupported"))
      yield Rendering.schedule(tx, tx.schedules.get(outcome.scheduleId).get)

  /** An edit is a new revision on the base revision the client read (ADR-004). */
  def revise(accountId: UUID, id: UUID, baseRevision: Long, draft: ScheduleDraft): Either[ApiFailure, Schedule] =
    uow.transaction: tx =>
      for
        current <- lockedSchedule(tx, accountId, id)
        _ <- currentRevision(tx, current, baseRevision)
        _ <- Either.cond(current.status == ScheduleStatus.Active, (), ApiFailure.invalid("status", "not_active"))
        rule <- ScheduleRules.validate(draft, clock.now().atZone(current.tz).toLocalDate)
        _ <- unchangedSince(tx, current, draft)
      yield
        lifecycle.editInTx(tx, id, rule, None, None, clock.now())
        Rendering.schedule(tx, tx.schedules.get(id).get)

  def changeScheduleState(
      accountId: UUID,
      id: UUID,
      baseRevision: Long,
      change: ScheduleChange
  ): Either[ApiFailure, Schedule] =
    uow.transaction: tx =>
      for
        current <- lockedSchedule(tx, accountId, id)
        _ <- currentRevision(tx, current, baseRevision)
        _ <- Either.cond(allowed(current.status, change), (), ApiFailure.invalid("change", "not_allowed"))
      yield
        val now = clock.now()
        change match
          case ScheduleChange.Pause  => lifecycle.pauseInTx(tx, id, now)
          case ScheduleChange.Resume => resume(tx, current, now)
          case ScheduleChange.End    => lifecycle.archiveInTx(tx, id, now)
        mirrorOnMedication(tx, current.medicationId, change, now)
        Rendering.schedule(tx, tx.schedules.get(id).get)

  // ---------- shared steps ----------

  private def replace(
      tx: Tx,
      current: StoredMedication,
      change: MedicationEdit => MedicationEdit
  ): Either[ApiFailure, StoredMedication] =
    val unchanged = MedicationEdit(
      current.id,
      current.accountId,
      current.updatedAt,
      current.name,
      current.doseAmount,
      current.doseUnit,
      current.instructions,
      current.status
    )
    tx.medications.replace(change(unchanged), clock.now()).toRight(conflictOn(tx, current.id))

  private def refreshSnapshots(tx: Tx, medication: StoredMedication): Unit =
    val snapshot = ScheduleLifecycle.snapshotOf(medication)
    tx.schedules
      .listForMedication(medication.id)
      .filter(_.status == ScheduleStatus.Active)
      .filter(schedule => !tx.revisions.latest(schedule.id).exists(_.doseSnapshot == snapshot))
      .foreach(schedule => lifecycle.changeDoseInTx(tx, schedule.id, snapshot, clock.now()))

  /** Brings the schedules to the medication's state. It runs on every state change, also when the row already had the
    * state: the chat pauses a schedule without touching the medication row.
    */
  private def moveSchedules(tx: Tx, medication: StoredMedication, target: MedicationStatus): Unit =
    val now = clock.now()
    val schedules = liveSchedules(tx, medication.id)
    target match
      case MedicationStatus.Archived => schedules.foreach(schedule => lifecycle.archiveInTx(tx, schedule.id, now))
      case MedicationStatus.Paused   =>
        schedules
          .filter(_.status == ScheduleStatus.Active)
          .foreach(schedule => lifecycle.pauseInTx(tx, schedule.id, now))
      case MedicationStatus.Active =>
        schedules.filter(_.status == ScheduleStatus.Paused).foreach(resume(tx, _, now))

  /** A resume restores the rule from before the pause with the snapshot of that time; a medication edited while paused
    * gets its current snapshot right after.
    */
  private def resume(tx: Tx, schedule: StoredSchedule, now: Instant): Unit =
    lifecycle.resumeInTx(tx, schedule.id, now)
    tx.medications.get(schedule.medicationId).foreach(refreshSnapshots(tx, _))

  /** The medication status mirrors its schedule for list views (`MedicationStatus`); an ended schedule leaves the
    * medication as it is, so a new schedule can follow.
    */
  private def mirrorOnMedication(tx: Tx, medicationId: UUID, change: ScheduleChange, now: Instant): Unit =
    val target = change match
      case ScheduleChange.Pause  => Some(MedicationStatus.Paused)
      case ScheduleChange.Resume => Some(MedicationStatus.Active)
      case ScheduleChange.End    => None
    for
      status <- target
      medication <- tx.medications.get(medicationId)
      if medication.status != status && medication.status != MedicationStatus.Archived
    do
      val edit = MedicationEdit(
        medication.id,
        medication.accountId,
        medication.updatedAt,
        medication.name,
        medication.doseAmount,
        medication.doseUnit,
        medication.instructions,
        status
      )
      tx.medications.replace(edit, now)

object Registry:
  val PreviewDays = 7
  private val DoseUnits = List("mg", "mcg", "g", "ml", "IU", "tablet", "capsule", "drop", "puff", "unit")

  private def ownedMedication(tx: Tx, accountId: UUID, id: UUID): Either[ApiFailure, StoredMedication] =
    tx.medications.get(id).filter(_.accountId == accountId).toRight(ApiFailure.notFound())

  private def ownedSchedule(tx: Tx, accountId: UUID, id: UUID): Either[ApiFailure, StoredSchedule] =
    tx.schedules.get(id).filter(_.accountId == accountId).toRight(ApiFailure.notFound())

  /** The row lock of the edit transaction, so the revision check and the new revision see the same row. */
  private def lockedSchedule(tx: Tx, accountId: UUID, id: UUID): Either[ApiFailure, StoredSchedule] =
    ownedSchedule(tx, accountId, id).flatMap(_ => tx.schedules.getForUpdate(id).toRight(ApiFailure.notFound()))

  private def liveSchedules(tx: Tx, medicationId: UUID): List[StoredSchedule] =
    tx.schedules.listForMedication(medicationId).filterNot(_.status == ScheduleStatus.Archived)

  private def zoneOf(tx: Tx, accountId: UUID): ZoneId =
    ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))

  private def conflictOn(tx: Tx, medicationId: UUID): ApiFailure =
    ApiFailure.versionConflict(Rendering.medication(tx, tx.medications.get(medicationId).get))

  private def currentVersion(tx: Tx, current: StoredMedication, version: Long): Either[ApiFailure, Unit] =
    Either.cond(
      Rendering.versionOf(current) == version,
      (),
      ApiFailure.versionConflict(Rendering.medication(tx, current))
    )

  private def currentRevision(tx: Tx, current: StoredSchedule, revision: Long): Either[ApiFailure, Unit] =
    Either.cond(
      current.currentRevision.toLong == revision,
      (),
      ApiFailure.versionConflict(Rendering.schedule(tx, current))
    )

  private def nameIsFree(tx: Tx, accountId: UUID, name: String, except: Option[UUID]): Either[ApiFailure, Unit] =
    val taken =
      tx.medications.findByNameNorm(accountId, name.toLowerCase(Locale.ROOT)).exists(row => !except.contains(row.id))
    Either.cond(!taken, (), ApiFailure.invalid("name", "duplicate"))

  /** The dates belong to the schedule row, which a revision does not rewrite. */
  private def unchangedSince(tx: Tx, current: StoredSchedule, draft: ScheduleDraft): Either[ApiFailure, Unit] =
    val view = Rendering.schedule(tx, current)
    val errors = List(
      Option.when(draft.pattern != view.pattern)(FieldError("pattern", "not_editable")),
      Option.when(draft.startDate.exists(_ != current.startDate))(FieldError("startDate", "not_editable")),
      Option.when(draft.endDate.isDefined && draft.endDate != current.endDate)(FieldError("endDate", "not_editable"))
    ).flatten
    Either.cond(errors.isEmpty, (), ApiFailure.invalid(errors))

  private def targetStatus(current: MedicationStatus, change: MedicationChange): Either[ApiFailure, MedicationStatus] =
    (current, change) match
      case (_, MedicationChange.Archive)  => Right(MedicationStatus.Archived)
      case (MedicationStatus.Archived, _) => Left(ApiFailure.invalid("change", "not_allowed"))
      case (_, MedicationChange.Pause)    => Right(MedicationStatus.Paused)
      case (_, MedicationChange.Resume)   => Right(MedicationStatus.Active)

  private def allowed(status: ScheduleStatus, change: ScheduleChange): Boolean = (status, change) match
    case (ScheduleStatus.Active, ScheduleChange.Pause)                       => true
    case (ScheduleStatus.Paused, ScheduleChange.Resume)                      => true
    case (ScheduleStatus.Active | ScheduleStatus.Paused, ScheduleChange.End) => true
    case _                                                                   => false
