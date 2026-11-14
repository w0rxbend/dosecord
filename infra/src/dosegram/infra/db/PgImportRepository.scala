package dosegram.infra.db

import dosegram.core.domain.imports.ImportCounts
import dosegram.core.domain.imports.ImportRecord
import dosegram.core.domain.imports.ImportSource
import dosegram.core.domain.imports.RecordJson
import dosegram.core.domain.sleep.Confidence
import dosegram.core.ports.DataDomain
import dosegram.core.ports.ImportJob
import dosegram.core.ports.ImportRepository
import dosegram.core.ports.ImportStatus
import dosegram.core.ports.KeyService
import dosegram.core.ports.StagedRecord
import dosegram.core.ports.StagedState

import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import scala.language.implicitConversions

/** `import_jobs`, `import_staging` and the imported rows of the entry tables (V15, ARCHITECTURE section 10.13) on the
  * connection of the surrounding transaction. Every statement names the account. A staged record is sealed with the
  * account's data key; the identity of a record is kept only as its keyed lookup hash, in the staging and in the entry
  * (`external_ref_hmac`). Rows are written in chunks, one statement per chunk over arrays, so a large import is a few
  * hundred statements, not one per record.
  */
final class PgImportRepository(conn: Connection, keys: KeyService) extends ImportRepository:
  import PgImportRepository.*

  private given Connection = conn

  private val JobColumns = "id, account_id, source, status, counts, created_at, committed_at, closed_at"

  private given RowMapper[ImportJob] = rs =>
    ImportJob(
      id = rs.uuid("id"),
      accountId = rs.uuid("account_id"),
      source = ImportSource.ofDbValue(rs.getString("source")).getOrElse(corrupt("source")),
      status = ImportStatus.ofDbValue(rs.getString("status")).getOrElse(corrupt("status")),
      counts = ImportCounts.read(rs.getString("counts")),
      createdAt = rs.instant("created_at"),
      committedAt = rs.optInstant("committed_at"),
      closedAt = rs.optInstant("closed_at")
    )

  override def create(job: ImportJob): Unit =
    sql"""INSERT INTO import_jobs (id, account_id, source, status, counts, created_at)
          VALUES (${job.id}, ${job.accountId}, ${job.source.dbValue}, ${job.status.dbValue},
                  ${Jsonb(ImportCounts.write(job.counts))}, ${job.createdAt})""".execute()
    ()

  override def find(accountId: UUID, id: UUID): Option[ImportJob] =
    SqlQuery(s"SELECT $JobColumns FROM import_jobs WHERE id = ? AND account_id = ?", Vector(id, accountId))
      .queryOne[ImportJob]()

  override def recent(accountId: UUID, limit: Int): List[ImportJob] =
    SqlQuery(
      s"SELECT $JobColumns FROM import_jobs WHERE account_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
      Vector(accountId, limit)
    ).query[ImportJob]()

  override def saveCounts(accountId: UUID, id: UUID, counts: ImportCounts): Unit =
    sql"""UPDATE import_jobs SET counts = ${Jsonb(ImportCounts.write(counts))}
          WHERE id = $id AND account_id = $accountId""".execute()
    ()

  override def transition(accountId: UUID, id: UUID, from: ImportStatus, to: ImportStatus, at: Instant): Boolean =
    val committedAt = Option.when(to == ImportStatus.Committed)(at)
    val closedAt = Option.when(to == ImportStatus.Cancelled || to == ImportStatus.Undone)(at)
    sql"""UPDATE import_jobs
          SET status = ${to.dbValue}, committed_at = COALESCE($committedAt, committed_at),
              closed_at = COALESCE($closedAt, closed_at)
          WHERE id = $id AND account_id = $accountId AND status = ${from.dbValue}""".execute() == 1

  override def stagedIdentities(accountId: UUID, jobId: UUID, identities: Set[String]): Set[String] =
    if identities.isEmpty then Set.empty
    else
      val byRef = identities.map(identity => base64(ref(accountId, identity)) -> identity).toMap
      val found = byRef.keys.toArray
        .grouped(ChunkSize)
        .flatMap: refs =>
          sql"""SELECT encode(external_ref_hmac, 'base64') FROM import_staging
              WHERE import_job_id = $jobId AND account_id = $accountId
                AND external_ref_hmac IN (SELECT decode(r, 'base64') FROM unnest($refs::text[]) AS r)"""
            .query[String]()
      found.flatMap(byRef.get).toSet

  override def stage(accountId: UUID, jobId: UUID, records: List[StagedRecord], at: Instant): Unit =
    records
      .grouped(ChunkSize)
      .foreach: chunk =>
        val ids = chunk.map(_.id.toString).toArray
        val domains = chunk.map(_.record.domain.code).toArray
        val payloads = chunk.map(r => base64(keys.sealText(payloadField(accountId, r.id), RecordJson.write(r.record))))
        val refs = chunk.map(r => base64(ref(accountId, r.record.identity))).toArray
        val states = chunk.map(_.state.dbValue).toArray
        sql"""INSERT INTO import_staging (id, account_id, import_job_id, domain, payload_enc, external_ref_hmac, state,
                                        created_at)
            SELECT r.id::uuid, $accountId, $jobId, r.domain, decode(r.payload, 'base64'), decode(r.ref, 'base64'),
                   r.state, $at
            FROM unnest($ids::text[], $domains::text[], ${payloads.toArray}::text[], $refs::text[], $states::text[])
                 AS r(id, domain, payload, ref, state)
            ON CONFLICT (import_job_id, external_ref_hmac) DO NOTHING""".execute()
    ()

  override def staged(accountId: UUID, jobId: UUID, state: StagedState): List[StagedRecord] =
    given RowMapper[StagedRecord] = rs =>
      val id = rs.uuid("id")
      val payload = keys.openText(payloadField(accountId, id), rs.getBytes("payload_enc"))
      StagedRecord(id, RecordJson.read(payload), state)
    sql"""SELECT id, payload_enc FROM import_staging
          WHERE import_job_id = $jobId AND account_id = $accountId AND state = ${state.dbValue}
          ORDER BY id""".query[StagedRecord]()

  override def write(accountId: UUID, jobId: UUID, records: List[StagedRecord], at: Instant): Set[UUID] =
    val written = for
      (domain, ofDomain) <- records.groupBy(_.record.domain).toList
      chunk <- ofDomain.grouped(ChunkSize).toList
      id <- insert(domain, accountId, jobId, chunk, at)
    yield id
    written.toSet

  override def voidEntries(accountId: UUID, jobId: UUID, at: Instant): Map[DataDomain, Int] =
    EntryTables.map: (domain, table) =>
      val voided = SqlQuery(
        s"""UPDATE $table SET voided_at = ?, void_reason = 'deleted'
            WHERE account_id = ? AND import_job_id = ? AND voided_at IS NULL""",
        Vector(at, accountId, jobId)
      ).execute()
      domain -> voided

  override def clearStaging(accountId: UUID, jobId: UUID): Int =
    sql"DELETE FROM import_staging WHERE import_job_id = $jobId AND account_id = $accountId".execute()

  override def openBefore(cutoff: Instant, limit: Int): List[ImportJob] =
    SqlQuery(
      s"SELECT $JobColumns FROM import_jobs WHERE status = 'open' AND created_at < ? ORDER BY created_at LIMIT ?",
      Vector(cutoff, limit)
    ).query[ImportJob]()

  private def ref(accountId: UUID, identity: String): Array[Byte] = keys.lookupHash(accountId, RefPurpose, identity)

  /** One chunk of one domain; returns the ids written. A record whose identity a live entry carries is skipped by the
    * unique index of V15, and a second main night on a sleep day by that of V1.
    */
  private def insert(
      domain: DataDomain,
      accountId: UUID,
      jobId: UUID,
      chunk: List[StagedRecord],
      at: Instant
  ): List[UUID] =
    val ids = chunk.map(_.id.toString).toArray
    val refs = chunk.map(r => base64(ref(accountId, r.record.identity))).toArray
    given RowMapper[UUID] = _.getObject(1, classOf[UUID])
    domain match
      case DataDomain.Hydration =>
        val waters = chunk.map(_.record).collect { case w: ImportRecord.Water => w }
        sql"""INSERT INTO hydration_entries (id, account_id, occurred_at, local_date, tz, source, import_job_id,
                                             external_ref_hmac, amount_ml, entered_value, entered_unit, created_at)
              SELECT r.id::uuid, $accountId, r.at::timestamptz, r.day::date, r.tz, 'import', $jobId,
                     decode(r.ref, 'base64'), r.ml::int, r.ml::numeric, 'ml', $at
              FROM unnest($ids::text[], ${waters.map(_.at.toString).toArray}::text[],
                          ${waters.map(_.localDate.toString).toArray}::text[], ${waters
            .map(_.zone.getId)
            .toArray}::text[],
                          $refs::text[], ${waters.map(_.millilitres.toString).toArray}::text[])
                   AS r(id, at, day, tz, ref, ml)
              ON CONFLICT DO NOTHING
              RETURNING id""".query[UUID]()
      case DataDomain.Mood =>
        val moods = chunk.map(_.record).collect { case m: ImportRecord.Mood => m }
        sql"""INSERT INTO mood_checkins (id, account_id, occurred_at, local_date, tz, source, import_job_id,
                                         external_ref_hmac, valence, energy, created_at)
              SELECT r.id::uuid, $accountId, r.at::timestamptz, r.day::date, r.tz, 'import', $jobId,
                     decode(r.ref, 'base64'), r.valence::smallint, r.energy::smallint, $at
              FROM unnest($ids::text[], ${moods.map(_.at.toString).toArray}::text[],
                          ${moods.map(_.localDate.toString).toArray}::text[], ${moods
            .map(_.zone.getId)
            .toArray}::text[],
                          $refs::text[], ${moods.map(_.valence.step.toString).toArray}::text[],
                          ${moods.map(_.energy.map(_.step.toString).orNull).toArray}::text[])
                   AS r(id, at, day, tz, ref, valence, energy)
              ON CONFLICT DO NOTHING
              RETURNING id""".query[UUID]()
      case DataDomain.Sleep =>
        val nights = chunk.map(_.record).collect { case s: ImportRecord.Sleep => s }
        val onsets = nights.map(_.interval.onset)
        val wakes = nights.map(_.interval.wake)
        sql"""INSERT INTO sleep_episodes (id, account_id, occurred_at, local_date, tz, source, import_job_id,
                                          external_ref_hmac, kind, onset_at, wake_at, tz_onset, tier, origin,
                                          onset_source, wake_source, confidence, quality, created_at)
              SELECT r.id::uuid, $accountId, r.wake::timestamptz, r.day::date, r.tz, 'import', $jobId,
                     decode(r.ref, 'base64'), r.kind, r.onset::timestamptz, r.wake::timestamptz, r.tz_onset,
                     r.tier::smallint, 'import_file', r.onset_source, r.wake_source, $ImportedConfidence,
                     r.quality::smallint, $at
              FROM unnest($ids::text[], ${wakes.map(_.at.toString).toArray}::text[],
                          ${nights
            .map(_.localDate.toString)
            .toArray}::text[], ${wakes.map(_.zone.getId).toArray}::text[],
                          $refs::text[], ${nights.map(_.kind.dbValue).toArray}::text[],
                          ${onsets.map(_.at.toString).toArray}::text[], ${onsets.map(_.zone.getId).toArray}::text[],
                          ${nights.map(_.tier.number.toString).toArray}::text[],
                          ${onsets.map(_.source.dbValue).toArray}::text[], ${wakes
            .map(_.source.dbValue)
            .toArray}::text[],
                          ${nights.map(_.quality.map(_.step.toString).orNull).toArray}::text[])
                   AS r(id, wake, day, tz, ref, kind, onset, tz_onset, tier, onset_source, wake_source, quality)
              ON CONFLICT DO NOTHING
              RETURNING id""".query[UUID]()
      case DataDomain.Nutrition =>
        val meals = chunk.zip(chunk.map(_.record)).collect { case (staged, m: ImportRecord.Meal) => (staged.id, m) }
        val words = meals.map((id, meal) =>
          meal.words.map(w => base64(keys.sealText(SealedColumns.MealWords.forAccount(accountId, id), w.value))).orNull
        )
        val hashes = meals.map((_, meal) =>
          meal.words.map(w => base64(keys.lookup(SealedColumns.MealWords, accountId, w.value))).orNull
        )
        sql"""INSERT INTO meals (id, account_id, occurred_at, local_date, tz, source, import_job_id, external_ref_hmac,
                                 meal_type, description_enc, description_hmac, created_at)
              SELECT r.id::uuid, $accountId, r.at::timestamptz, r.day::date, r.tz, 'import', $jobId,
                     decode(r.ref, 'base64'), r.meal_type, decode(r.words, 'base64'), decode(r.words_hmac, 'base64'), $at
              FROM unnest($ids::text[], ${meals.map(_._2.at.toString).toArray}::text[],
                          ${meals.map(_._2.localDate.toString).toArray}::text[],
                          ${meals.map(_._2.zone.getId).toArray}::text[], $refs::text[],
                          ${meals.map(_._2.mealType.dbValue).toArray}::text[], ${words.toArray}::text[],
                          ${hashes.toArray}::text[])
                   AS r(id, at, day, tz, ref, meal_type, words, words_hmac)
              ON CONFLICT DO NOTHING
              RETURNING id""".query[UUID]()
      case DataDomain.Medication => throw IllegalArgumentException("an import writes no medication record (R-IMX-11)")
end PgImportRepository

object PgImportRepository:

  /** The lookup purpose of `external_ref_hmac`: separates it from every other lookup column of the account. */
  val RefPurpose = "import_ref"

  private val ChunkSize = 1000

  /** An imported night's confidence: its edges come from the file as given, like two entered edges (R-SLP-12;
    * supervisor decision, 2026-11-14).
    */
  private val ImportedConfidence: BigDecimal = BigDecimal(Confidence.BothExplicit.hundredths) / 100

  /** The entry tables an import writes, by domain. */
  private val EntryTables: Map[DataDomain, String] = Map(
    DataDomain.Hydration -> "hydration_entries",
    DataDomain.Mood -> "mood_checkins",
    DataDomain.Sleep -> "sleep_episodes",
    DataDomain.Nutrition -> "meals"
  )

  /** The place a staged record is sealed for: the account, the staging table and the row. */
  def payloadField(accountId: UUID, id: UUID) = SealedColumns.ImportPayload.forAccount(accountId, id)

  private def base64(bytes: Array[Byte]): String = Base64.getEncoder.encodeToString(bytes)

  private def corrupt(column: String): Nothing = throw IllegalStateException(s"import_jobs.$column is corrupt")
end PgImportRepository
