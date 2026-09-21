package dosecord.infra.db

import dosecord.contracts.AccountId
import dosecord.contracts.IdentityId
import dosecord.contracts.PlatformIdentity
import dosecord.contracts.Principal
import dosecord.core.ports.IdentityRepository

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

final class PgIdentityRepository(conn: Connection) extends IdentityRepository:
  private given Connection = conn

  private given RowMapper[Principal] = rs =>
    val userId = Option(rs.getObject("user_id", classOf[UUID]))
    Principal(IdentityId(rs.uuid("id")), userId.map(AccountId(_)), linked = userId.isDefined)

  override def find(actor: PlatformIdentity): Option[Principal] =
    sql"""SELECT id, user_id FROM platform_identities
          WHERE vendor = ${actor.vendor} AND vendor_user_id = ${actor.vendorUserId} AND unlinked_at IS NULL"""
      .queryOne[Principal]()

  override def resolve(actor: PlatformIdentity, now: Instant): Principal =
    find(actor) match
      case Some(principal) =>
        sql"""UPDATE platform_identities SET last_seen_at = $now WHERE id = ${principal.identityId.uuid}""".execute()
        principal
      case None =>
        val id = UUID.randomUUID()
        sql"""INSERT INTO platform_identities (id, vendor, vendor_user_id, vendor_username, last_seen_at)
              VALUES ($id, ${actor.vendor}, ${actor.vendorUserId}, ${actor.displayName}, $now)
              ON CONFLICT DO NOTHING""".execute()
        // A concurrent first-contact on another chat may have won the insert; re-read either way.
        find(actor).getOrElse(Principal(IdentityId(id), None, linked = false))
