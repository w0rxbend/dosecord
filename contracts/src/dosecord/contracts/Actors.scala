package dosecord.contracts

import upickle.default.ReadWriter

/** Identity reference carried on every envelope (transcribed from the retired prototype's
  * `shared/contracts/actors.py`). The vendor is an open string, not a closed enum (ADR-005). `accountId` is empty
  * during signup, link and first-contact flows; it is stamped by the core, never trusted from the wire (R8).
  */
final case class Actor(
    vendor: String,
    vendorUserId: String,
    vendorUsername: Option[String] = None,
    accountId: Option[AccountId] = None
) derives ReadWriter:
  require(vendor.nonEmpty, "vendor must not be empty")
  require(vendorUserId.nonEmpty, "vendorUserId must not be empty")

  /** CloudEvents `subject` for envelopes from this actor. */
  def subject: String =
    accountId match
      case Some(account) => s"account:${account.uuid}"
      case scala.None    => s"platform:$vendor:$vendorUserId"
