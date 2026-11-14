package dosegram.core.ports

import dosegram.core.domain.imports.ImportCounts
import dosegram.core.domain.imports.ImportRecord
import dosegram.core.domain.imports.ImportSource

import java.time.Instant
import java.util.UUID

/** The state of an import (V15): collecting batches, committed into the entry tables, cancelled before the commit, or
  * undone after it.
  */
enum ImportStatus(val dbValue: String):
  case Open extends ImportStatus("open")
  case Committed extends ImportStatus("committed")
  case Cancelled extends ImportStatus("cancelled")
  case Undone extends ImportStatus("undone")

object ImportStatus:
  def ofDbValue(value: String): Option[ImportStatus] = values.find(_.dbValue == value)

/** One import of an account (`import_jobs`): metadata and counts only, never a value of the file. */
final case class ImportJob(
    id: UUID,
    accountId: UUID,
    source: ImportSource,
    status: ImportStatus,
    counts: ImportCounts,
    createdAt: Instant,
    committedAt: Option[Instant],
    closedAt: Option[Instant]
)

/** What a staged record is: new, a duplicate of a record the account or the import already holds, or in conflict with
  * one (a night that overlaps another). Only new records are written at the commit.
  */
enum StagedState(val dbValue: String):
  case Fresh extends StagedState("new")
  case Duplicate extends StagedState("duplicate")
  case Conflict extends StagedState("conflict")

final case class StagedRecord(id: UUID, record: ImportRecord, state: StagedState)

/** The import jobs, their staging and the entries they write (ARCHITECTURE section 10.13, V15). Every method is scoped
  * to one account. The identity of a record (R-IMX-9) is stored only as a keyed hash of the account
  * (`external_ref_hmac`), so equal identities match without the values being stored in the clear.
  */
trait ImportRepository:

  def create(job: ImportJob): Unit
  def find(accountId: UUID, id: UUID): Option[ImportJob]

  /** The account's imports, newest first. */
  def recent(accountId: UUID, limit: Int): List[ImportJob]

  def saveCounts(accountId: UUID, id: UUID, counts: ImportCounts): Unit

  /** Moves an import from `from` to `to`, stamping the commit or the close; `false` when it was not in `from`. */
  def transition(accountId: UUID, id: UUID, from: ImportStatus, to: ImportStatus, at: Instant): Boolean

  /** The identities among `identities` that the import already staged. */
  def stagedIdentities(accountId: UUID, jobId: UUID, identities: Set[String]): Set[String]

  /** Stages the records with their states, sealed, each with the keyed hash of its identity. */
  def stage(accountId: UUID, jobId: UUID, records: List[StagedRecord], at: Instant): Unit

  /** The staged records of the import in one state, in the order they were staged. */
  def staged(accountId: UUID, jobId: UUID, state: StagedState): List[StagedRecord]

  /** Writes the records as entries of the import (`source` 'import', `import_job_id`, `external_ref_hmac`); a record
    * whose identity a live entry of the account already carries is not written. Returns the ids written.
    */
  def write(accountId: UUID, jobId: UUID, records: List[StagedRecord], at: Instant): Set[UUID]

  /** Voids every live entry the import wrote; returns the count per domain. */
  def voidEntries(accountId: UUID, jobId: UUID, at: Instant): Map[DataDomain, Int]

  /** Deletes the import's staging; returns the rows deleted. */
  def clearStaging(accountId: UUID, jobId: UUID): Int

  /** The open imports of every account created before `cutoff`: the sweep cancels them. */
  def openBefore(cutoff: Instant, limit: Int): List[ImportJob]
