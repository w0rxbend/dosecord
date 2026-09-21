package dosecord.core.ports

import dosecord.contracts.PlatformIdentity
import dosecord.contracts.Principal

import java.time.Instant

/** The IdentityResolver port (DESIGN.md sections 3 and 4.6 step 4): identity resolution is core-only — there is no
  * field for an account id on the wire (R8).
  */
trait IdentityRepository:
  /** Find-or-create the platform identity; an unknown actor gets an unlinked (anonymous) principal (DESIGN.md section
    * 6).
    */
  def resolve(actor: PlatformIdentity, now: Instant): Principal

  /** Read-only lookup (the one indexed read of the `opensForm` ack path, ADR-013). */
  def find(actor: PlatformIdentity): Option[Principal]
