package dosegram.core.ports

import java.util.UUID

/** The consent purposes that the statistics, hydration and mood use cases check. The `id` strings are the canonical
  * purpose identifiers of PRODUCT_SPEC R-IDC-6; the consent ledger of ROADMAP G2.8 owns the full list.
  */
enum ConsentPurpose(val id: String):
  case Medication extends ConsentPurpose("medication")
  case Hydration extends ConsentPurpose("hydration")
  case Mood extends ConsentPurpose("mood")
  case CrossDomainPatterns extends ConsentPurpose("cross_domain_patterns")

/** Whether an account has an active consent for a purpose (PRODUCT_SPEC R-IDC-9: a feature of a domain without an
  * active consent is unavailable, and its data is not shown).
  */
trait ConsentCheck:
  def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean
