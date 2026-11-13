package dosegram.core.domain

/** Why a tracking entry was voided: the `void_reason` entry column of ARCHITECTURE section 10.1. A voided entry stays
  * in its table and leaves every total; `Superseded` marks the old row of a correction.
  */
enum VoidReason(val dbValue: String):
  case Undone extends VoidReason("undone")
  case Deleted extends VoidReason("deleted")
  case Superseded extends VoidReason("superseded")
