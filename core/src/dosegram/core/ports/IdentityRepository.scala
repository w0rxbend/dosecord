package dosegram.core.ports

import dosegram.contracts.IdentityId
import dosegram.contracts.PlatformIdentity
import dosegram.contracts.Principal

import java.time.Instant

/** The IdentityResolver port (DESIGN.md sections 3 and 4.6 step 4): identity resolution is core-only — there is no
  * field for an account id on the wire (R8).
  */
trait IdentityRepository:
  /** Find-or-create the platform identity; an unknown actor gets an unlinked (anonymous) principal (DESIGN.md section
    * 6).
    */
  def resolve(actor: PlatformIdentity, now: Instant): Principal

  /** Read-only lookup of an identity by its vendor key. */
  def find(actor: PlatformIdentity): Option[Principal]

  /** Records the chat the identity last wrote from as its direct chat, the chat its delivery channels send to. Adapters
    * deliver direct chats only (a Telegram group update is dropped), so every inbound chat is one.
    */
  def recordDirectChat(identityId: IdentityId, chatId: String): Unit
