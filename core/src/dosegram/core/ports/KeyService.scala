package dosegram.core.ports

import java.util.UUID

/** Whose data key seals a value (ARCHITECTURE SEC-01, SEC-04): an account's own key, or a service key for rows that
  * belong to no account (the bytes of a drawn image).
  */
enum KeyOwner:
  case Account(id: UUID)
  case Service(purpose: String)

/** Where a sealed value lives. All four parts are bound into the ciphertext as associated data, so a value moved to
  * another account, table, column or row no longer opens (SEC-01).
  */
final case class SealedField(owner: KeyOwner, table: String, column: String, row: String)

/** A sealed value that does not open: the wrong key, another place, or changed bytes. It never carries key material or
  * plaintext.
  */
final class SealBroken(message: String) extends RuntimeException(message)

/** The key service of ARCHITECTURE section 15.2: field-level envelope encryption of Class A values with the owner's
  * data key, and the keyed lookup hash that lets an exact-match search run without the plaintext (the `name_hmac` that
  * replaces `name_norm`, ROADMAP G2.4). Implementations never log a key or a plaintext.
  */
trait KeyService:

  /** Encrypts `plaintext` for `field` under the owner's active data key. */
  def seal(field: SealedField, plaintext: Array[Byte]): Array[Byte]

  /** Decrypts what [[seal]] returned for the same `field`; throws [[SealBroken]] when it does not authenticate. */
  def open(field: SealedField, value: Array[Byte]): Array[Byte]

  /** A keyed hash of `value` for exact-match lookups of one account; `purpose` separates the lookup columns. The caller
    * normalises `value` first, the same way on write and on search.
    */
  def lookupHash(account: UUID, purpose: String, value: String): Array[Byte]
