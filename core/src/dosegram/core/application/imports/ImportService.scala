package dosegram.core.application.imports

import dosegram.core.domain.imports.CsvRefusal
import dosegram.core.domain.imports.DeviceRecord
import dosegram.core.domain.imports.DomainTally
import dosegram.core.domain.imports.DosegramCsv
import dosegram.core.domain.imports.ImportBounds
import dosegram.core.domain.imports.ImportCounts
import dosegram.core.domain.imports.ImportRecord
import dosegram.core.domain.imports.ImportSource
import dosegram.core.domain.imports.RecordIdentity
import dosegram.core.domain.imports.RowError
import dosegram.core.domain.imports.RowOutcome
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.IdSource
import dosegram.core.ports.ImportJob
import dosegram.core.ports.ImportStatus
import dosegram.core.ports.StagedRecord
import dosegram.core.ports.StagedState
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** One batch of an import: rows of a Dosegram CSV file (with its header; `firstLine` is the file line of its first data
  * row), or records a health export was reduced to on the device, with the number of records the device ignored
  * (PRODUCT_SPEC R-IMX-7, R-IMX-8). `firstIndex` numbers the device records for the error list.
  */
enum ImportBatch:
  case CsvRows(text: String, firstLine: Int)
  case DeviceRecords(records: List[DeviceRecord], ignored: Int, firstIndex: Int)

  def source: ImportSource = this match
    case _: CsvRows       => ImportSource.DosegramCsv
    case _: DeviceRecords => ImportSource.AppleHealth

enum ImportRefusal:
  case ConsentMissing
  case UnknownAccount
  case NotFound
  case NotOpen
  case AlreadyClosed
  case WrongSource
  case Unreadable
  case UnknownColumns
  case TooManyRecords

/** Imports (ROADMAP G10.5, G10.7; PRODUCT_SPEC R-IMX-7 to R-IMX-13). An import is one job: batches are read, checked
  * and staged with a state, the preview is the job's counts, the commit writes the new records as entries of the job,
  * and undo voids exactly them. Starting and committing need the `import` consent; each record also needs its domain's
  * consent, and a record without it is counted, not stored. Nothing of the file is kept: a CSV batch is read in memory,
  * and the staging holds normalised records until the commit or the cancel.
  */
final class ImportService(clock: Clock, ids: IdSource):
  import ImportService.*

  def start(tx: Tx, accountId: UUID, source: ImportSource): Either[ImportRefusal, ImportJob] =
    for
      _ <- consented(tx, accountId)
      _ <- tx.accounts.timezoneOf(accountId).toRight(ImportRefusal.UnknownAccount)
    yield
      val now = clock.now()
      val job = ImportJob(ids.next(now), accountId, source, ImportStatus.Open, ImportCounts(), now, None, None)
      tx.imports.create(job)
      job

  def find(tx: Tx, accountId: UUID, id: UUID): Either[ImportRefusal, ImportJob] =
    tx.imports.find(accountId, id).toRight(ImportRefusal.NotFound)

  def recent(tx: Tx, accountId: UUID): List[ImportJob] = tx.imports.recent(accountId, ListLimit)

  /** Reads one batch into the preview. A refused batch changes nothing. */
  def addBatch(tx: Tx, accountId: UUID, id: UUID, batch: ImportBatch): Either[ImportRefusal, ImportJob] =
    for
      _ <- consented(tx, accountId)
      job <- open(tx, accountId, id)
      _ <- Either.cond(job.source == batch.source, (), ImportRefusal.WrongSource)
      zone <- zoneOf(tx, accountId)
      read <- readBatch(batch, zone)
      granted = DataDomain.values.filter(d => tx.consents.isGranted(accountId, d.purpose)).toSet
      (allowed, refusedByConsent) = read.records.partition(r => granted(r.record.domain))
      _ <- Either.cond(job.counts.staged + allowed.size <= ImportBounds.MaxRecords, (), ImportRefusal.TooManyRecords)
    yield
      val staged = classify(tx, accountId, id, allowed.map(_.record))
      tx.imports.stage(accountId, id, staged, clock.now())
      val counts = job.counts + read.counts(staged, allowed.count(_.noOffset), refusedByConsent.size)
      tx.imports.saveCounts(accountId, id, counts)
      job.copy(counts = counts)

  /** Writes the new records of an open import. A record whose domain lost its consent since the preview is not written;
    * a record that became a duplicate since is counted as one.
    */
  def commit(tx: Tx, accountId: UUID, id: UUID): Either[ImportRefusal, ImportJob] =
    for
      _ <- consented(tx, accountId)
      job <- open(tx, accountId, id)
    yield
      val now = clock.now()
      val fresh = tx.imports.staged(accountId, id, StagedState.Fresh)
      val (allowed, refused) = fresh.partition(r => tx.consents.isGranted(accountId, r.record.domain.purpose))
      val written = tx.imports.write(accountId, id, allowed, now)
      tx.imports.clearStaging(accountId, id)
      tx.imports.transition(accountId, id, ImportStatus.Open, ImportStatus.Committed, now)
      val counts = committedCounts(job.counts, allowed, written, refused.size)
      tx.imports.saveCounts(accountId, id, counts)
      val domains = allowed.filter(r => written(r.id)).map(_.record.domain).distinct.sortBy(_.ordinal)
      tx.auditLog.record(audit(AuditEventType.ImportCommitted, accountId, id, "commit", domains))
      job.copy(status = ImportStatus.Committed, counts = counts, committedAt = Some(now))

  /** Cancels an open import, or undoes a committed one: voids exactly the entries it wrote (AC-IMX-6). Needs no
    * consent: removing records is always allowed.
    */
  def remove(tx: Tx, accountId: UUID, id: UUID): Either[ImportRefusal, ImportJob] =
    val now = clock.now()
    tx.imports
      .find(accountId, id)
      .toRight(ImportRefusal.NotFound)
      .flatMap: job =>
        job.status match
          case ImportStatus.Open =>
            tx.imports.clearStaging(accountId, id)
            tx.imports.transition(accountId, id, ImportStatus.Open, ImportStatus.Cancelled, now)
            Right(job.copy(status = ImportStatus.Cancelled, closedAt = Some(now)))
          case ImportStatus.Committed =>
            val voided = tx.imports.voidEntries(accountId, id, now)
            tx.imports.transition(accountId, id, ImportStatus.Committed, ImportStatus.Undone, now)
            val domains = voided.filter(_._2 > 0).keys.toList.sortBy(_.ordinal)
            tx.auditLog.record(audit(AuditEventType.ImportUndone, accountId, id, "undo", domains))
            Right(job.copy(status = ImportStatus.Undone, closedAt = Some(now)))
          case ImportStatus.Cancelled | ImportStatus.Undone => Left(ImportRefusal.AlreadyClosed)

  /** Cancels the imports left open for longer than [[Abandoned]], so no staged record outlives a day. */
  def sweep(tx: Tx): Int =
    val now = clock.now()
    tx.imports
      .openBefore(now.minus(Abandoned), SweepBatch)
      .count: job =>
        tx.imports.clearStaging(job.accountId, job.id)
        tx.imports.transition(job.accountId, job.id, ImportStatus.Open, ImportStatus.Cancelled, now)

  private def consented(tx: Tx, accountId: UUID): Either[ImportRefusal, Unit] =
    Either.cond(tx.consents.isGranted(accountId, ConsentPurpose.Import), (), ImportRefusal.ConsentMissing)

  private def open(tx: Tx, accountId: UUID, id: UUID): Either[ImportRefusal, ImportJob] =
    tx.imports.find(accountId, id) match
      case None                                         => Left(ImportRefusal.NotFound)
      case Some(job) if job.status != ImportStatus.Open => Left(ImportRefusal.NotOpen)
      case Some(job)                                    => Right(job)

  private def zoneOf(tx: Tx, accountId: UUID): Either[ImportRefusal, ZoneId] =
    tx.accounts.timezoneOf(accountId).map(ZoneId.of).toRight(ImportRefusal.UnknownAccount)

  private def readBatch(batch: ImportBatch, zone: ZoneId): Either[ImportRefusal, ReadBatch] =
    val now = clock.now()
    batch match
      case ImportBatch.CsvRows(text, firstLine) =>
        DosegramCsv
          .read(text, zone, now, firstLine)
          .left
          .map:
            case CsvRefusal.Unreadable     => ImportRefusal.Unreadable
            case CsvRefusal.UnknownColumns => ImportRefusal.UnknownColumns
            case CsvRefusal.TooManyRows    => ImportRefusal.TooManyRecords
          .map(parsed => ReadBatch.of(parsed.outcomes, ignored = 0))
      case ImportBatch.DeviceRecords(records, ignored, firstIndex) =>
        if records.size > ImportBounds.MaxBatchRecords then Left(ImportRefusal.TooManyRecords)
        else
          val outcomes = records.zipWithIndex.map: (record, index) =>
            record.normalised(zone, now) match
              case Right(normalised) => RowOutcome.Parsed(firstIndex + index, normalised, noOffset = false)
              case Left(problem)     => RowOutcome.Refused(firstIndex + index, "record", problem)
          Right(ReadBatch.of(outcomes, ignored))

  /** The state of each record: a duplicate of a record the account holds, the import staged or the batch repeats; a
    * night in conflict with one that overlaps it, or a second main night on its sleep day; otherwise new.
    */
  private def classify(tx: Tx, accountId: UUID, jobId: UUID, records: List[ImportRecord]): List[StagedRecord] =
    val identities = records.map(_.identity).toSet
    val known = tx.imports.stagedIdentities(accountId, jobId, identities) ++ liveIdentities(tx, accountId, records)
    val nights = Nights(liveNights(tx, accountId, jobId, records))
    val seen = scala.collection.mutable.Set.empty[String]
    records.map: record =>
      val state =
        if known(record.identity) || seen(record.identity) then StagedState.Duplicate
        else
          record match
            case sleep: ImportRecord.Sleep if nights.conflicts(sleep) => StagedState.Conflict
            case sleep: ImportRecord.Sleep                            =>
              nights.add(sleep)
              StagedState.Fresh
            case _ => StagedState.Fresh
      seen += record.identity
      StagedRecord(ids.next(clock.now()), record, state)

  /** The identities of the account's live entries on the dates the records cover, domain by domain. */
  private def liveIdentities(tx: Tx, accountId: UUID, records: List[ImportRecord]): Set[String] =
    records
      .groupBy(_.domain)
      .toList
      .flatMap: (domain, ofDomain) =>
        val range = rangeOf(ofDomain)
        domain match
          case DataDomain.Hydration  => tx.tracking.hydration.listLive(accountId, range).map(RecordIdentity.of)
          case DataDomain.Mood       => tx.tracking.mood.listLive(accountId, range).map(RecordIdentity.of)
          case DataDomain.Sleep      => tx.tracking.sleep.liveEpisodes(accountId, range).map(RecordIdentity.of)
          case DataDomain.Nutrition  => tx.tracking.meals.listLive(accountId, range).map(RecordIdentity.of)
          case DataDomain.Medication => Nil
      .toSet

  /** The nights a new night must not overlap: the live episodes around its days and the new nights already staged. */
  private def liveNights(tx: Tx, accountId: UUID, jobId: UUID, records: List[ImportRecord]): List[Night] =
    val sleeps = records.collect { case sleep: ImportRecord.Sleep => sleep }
    if sleeps.isEmpty then Nil
    else
      val range = rangeOf(sleeps)
      val around = DateRange.between(range.start.minusDays(1), range.end.plusDays(1))
      val live = tx.tracking.sleep
        .liveEpisodes(accountId, around)
        .map(e => Night(e.interval.onset.at, e.interval.wake.at, e.kind, e.sleepDay))
      val staged = tx.imports
        .staged(accountId, jobId, StagedState.Fresh)
        .collect { case StagedRecord(_, sleep: ImportRecord.Sleep, _) => Night.of(sleep) }
      live ++ staged

  private def committedCounts(
      preview: ImportCounts,
      allowed: List[StagedRecord],
      written: Set[UUID],
      refusedByConsent: Int
  ): ImportCounts =
    val byDomain = allowed
      .groupBy(_.record.domain)
      .map: (domain, records) =>
        val imported = records.count(r => written(r.id))
        val tally = preview.tally(domain)
        domain -> tally.copy(imported = imported, duplicate = tally.duplicate + records.size - imported)
    preview.copy(domains = preview.domains ++ byDomain, withoutConsent = preview.withoutConsent + refusedByConsent)

  private def audit(
      eventType: AuditEventType,
      accountId: UUID,
      jobId: UUID,
      action: String,
      domains: List[DataDomain]
  ): AuditEvent =
    AuditEvent.byUser(
      eventType,
      accountId,
      "import_job",
      Some(jobId),
      action,
      AuditChannel.MiniApp,
      domains.map(_.code)
    )
end ImportService

object ImportService:
  val ListLimit = 20

  /** An open import older than this is cancelled by the sweep. */
  val Abandoned: Duration = Duration.ofHours(24)
  private val SweepBatch = 100

  private def rangeOf(records: List[ImportRecord]): DateRange =
    val dates = records.map(_.localDate)
    DateRange.between(dates.min, dates.max)

  /** A record that parsed, and whether its time had no offset. */
  private final case class Read(record: ImportRecord, noOffset: Boolean)

  /** What a batch held before the states: the records, and the counts of the rows that are not records. */
  private final case class ReadBatch(records: List[Read], refused: List[RowError], skipped: Int, ignored: Int):

    /** The batch's part of the preview, once its records have their states. */
    def counts(staged: List[StagedRecord], noOffset: Int, withoutConsent: Int): ImportCounts =
      val domains = staged
        .groupBy(_.record.domain)
        .map: (domain, records) =>
          domain -> DomainTally(
            fresh = records.count(_.state == StagedState.Fresh),
            duplicate = records.count(_.state == StagedState.Duplicate),
            conflict = records.count(_.state == StagedState.Conflict)
          )
      val dates = staged.map(_.record.localDate)
      ImportCounts(
        domains = domains,
        invalid = refused.size,
        skipped = skipped,
        ignored = ignored,
        withoutConsent = withoutConsent,
        noOffset = noOffset,
        errors = refused.take(ImportBounds.MaxListedErrors),
        from = dates.minOption,
        to = dates.maxOption
      )

  private object ReadBatch:
    def of(outcomes: List[RowOutcome], ignored: Int): ReadBatch =
      ReadBatch(
        records = outcomes.collect { case RowOutcome.Parsed(_, record, noOffset) => Read(record, noOffset) },
        refused =
          outcomes.collect { case RowOutcome.Refused(line, field, problem) => RowError(line, field, problem.code) },
        skipped = outcomes.count(_.isInstanceOf[RowOutcome.Skipped]),
        ignored = ignored
      )

  /** A night as the overlap rule sees it. */
  private final case class Night(onset: Instant, wake: Instant, kind: SleepKind, sleepDay: java.time.LocalDate):
    def overlaps(other: Night): Boolean = onset.isBefore(other.wake) && other.onset.isBefore(wake)

  private object Night:
    def of(sleep: ImportRecord.Sleep): Night =
      Night(sleep.interval.onset.at, sleep.interval.wake.at, sleep.kind, sleep.interval.sleepDay)

  /** The nights so far; a new one conflicts when it overlaps one, or is a second main night on a sleep day (one night
    * per sleep day, V1 `uq_sleep_episodes_main_per_day`).
    */
  private final class Nights(initial: List[Night]):
    private val all = scala.collection.mutable.ListBuffer.from(initial)

    def conflicts(sleep: ImportRecord.Sleep): Boolean =
      val night = Night.of(sleep)
      all.exists(other =>
        other.overlaps(night) || (night.kind == SleepKind.Main && other.kind == SleepKind.Main &&
          other.sleepDay == night.sleepDay)
      )

    def add(sleep: ImportRecord.Sleep): Unit = all += Night.of(sleep)
end ImportService
