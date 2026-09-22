package dosecord.core.ports

import dosecord.contracts.AccountId
import dosecord.contracts.IdentityId

import java.time.Instant

/** `users` / `platform_identities` / `delivery_channels` (DESIGN.md section 6, ROADMAP M0.12d): account creation is one
  * transactional write — the `users` row (handle and display name left NULL: generated/neutral, never the vendor
  * nickname, K8), the link on the current platform identity, and the primary delivery channel.
  */
trait AccountRepository:

  /** Creates the account for `identityId` and returns its id. Caller guards on `principal.linked`; a concurrent create
    * surfaces as a constraint violation and rolls the event transaction back.
    */
  def createAccount(identityId: IdentityId, timezone: String, now: Instant): AccountId
