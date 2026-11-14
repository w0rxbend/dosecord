package dosegram.core.ports

import java.time.Instant
import java.util.UUID

/** A domain whose records can be deleted on their own (PRODUCT_SPEC R-IDC-20, ARCHITECTURE SEC-17), with the consent
  * that gates it.
  */
enum DataDomain(val code: String, val purpose: ConsentPurpose):
  case Medication extends DataDomain("medication", ConsentPurpose.Medication)
  case Hydration extends DataDomain("hydration", ConsentPurpose.Hydration)
  case Mood extends DataDomain("mood", ConsentPurpose.Mood)
  case Sleep extends DataDomain("sleep", ConsentPurpose.Sleep)
  case Nutrition extends DataDomain("nutrition", ConsentPurpose.Nutrition)

object DataDomain:
  def ofCode(code: String): Option[DataDomain] = values.find(_.code == code)
  def ofPurpose(purpose: ConsentPurpose): Option[DataDomain] = values.find(_.purpose == purpose)

/** What a deletion did: the tombstone that stands for the account from now on, and the rows deleted per table. Counts
  * only, never content (ROADMAP G2.17).
  */
final case class ErasureReceipt(tombstoneId: UUID, rowCounts: Map[String, Int]):
  def total: Int = rowCounts.values.sum

/** The erasure job on the caller's transaction (ARCHITECTURE SEC-16, SEC-17): deletes under the erasure role, in
  * dependency order, and writes the receipt. Every table of the schema has an erasure decision, so a new table cannot
  * be forgotten.
  */
trait ErasureRepository:

  /** Deletes every row of the account, the stores of the edges that name it included, replaces the account in its audit
    * rows by `tombstoneId`, and writes the receipt.
    */
  def eraseAccount(accountId: UUID, tombstoneId: UUID, now: Instant): ErasureReceipt

  /** Deletes the rows and rollups of one domain of the account, and no row of another domain, and writes the receipt.
    */
  def eraseDomain(accountId: UUID, domain: DataDomain, tombstoneId: UUID, now: Instant): ErasureReceipt

/** The purge list (ARCHITECTURE SEC-06, SEC-16): the deleted accounts, kept outside the database so that a restored
  * backup deletes them again before it serves traffic. An append is durable when it returns, and a failed append
  * throws, so a deletion that cannot be listed does not happen.
  */
trait PurgeList:
  def append(accountId: UUID, at: Instant): Unit
