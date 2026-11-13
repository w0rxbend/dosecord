package dosegram.core.ports

import java.time.Instant
import java.util.UUID

/** The consent purposes that the statistics, hydration and mood use cases check. The `id` strings are the canonical
  * purpose identifiers of PRODUCT_SPEC R-IDC-6; the consent ledger of ROADMAP G2.8 owns the full list.
  */
enum ConsentPurpose(val id: String):
  case Medication extends ConsentPurpose("medication")
  case Hydration extends ConsentPurpose("hydration")
  case Mood extends ConsentPurpose("mood")
  case CrossDomainPatterns extends ConsentPurpose("cross_domain_patterns")

  /** Delivery of personal report images into the chat (BOT_UX BUX-IMG-04). */
  case ImagesInChat extends ConsentPurpose("images_in_chat")

object ConsentPurpose:
  def ofId(id: String): Option[ConsentPurpose] = values.find(_.id == id)

/** Whether an account has an active consent for a purpose (PRODUCT_SPEC R-IDC-9: a feature of a domain without an
  * active consent is unavailable, and its data is not shown).
  */
trait ConsentCheck:
  def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean

/** What the user agreed to: the version of the privacy notice and the statement shown at the confirming tap. */
final case class ConsentNotice(policyVersion: Int, statement: String):
  require(policyVersion > 0, "a notice version is positive")
  require(statement.nonEmpty, "a consent statement must not be empty")

/** One purpose that an account has granted, as the ledger holds it. */
final case class GrantedConsent(purpose: ConsentPurpose, grantedAt: Instant, policyVersion: Int)

/** The consent ledger (ARCHITECTURE section 10.2): append-only events and their current state per purpose. A consent is
  * recorded in chat only, on the confirming tap (PRODUCT_SPEC R-IDC-7), and each purpose is revocable on its own
  * (R-IDC-6).
  */
trait ConsentLedger extends ConsentCheck:

  /** Records a grant. `false` when the purpose was already granted under the same notice version: nothing is written.
    */
  def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, at: Instant): Boolean

  /** Records a withdrawal. `false` when the purpose was not granted: nothing is written. */
  def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean

  /** The purposes granted now, in the order they were granted. */
  def granted(accountId: UUID): List[GrantedConsent]
