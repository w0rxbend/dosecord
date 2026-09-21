package dosecord.infra.db

import dosecord.core.ports.DeliveryChannelRepository
import dosecord.core.ports.DeliveryTarget

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `delivery_channels` (DESIGN.md section 6): the healthy primary channels the reminder loop enqueues to.
  * Fallback ordering, broadcast and dead-channel handling are the dispatcher's concern (M1.7, M5.2).
  */
final class PgDeliveryChannelRepository(conn: Connection) extends DeliveryChannelRepository:
  private given Connection = conn

  private given RowMapper[DeliveryTarget] = rs =>
    DeliveryTarget(
      channelId = rs.uuid("id"),
      vendor = rs.getString("vendor"),
      chatId = rs.optString("dm_channel_id")
    )

  override def activePrimaryChannels(accountId: UUID): List[DeliveryTarget] =
    sql"""SELECT dc.id, pi.vendor, pi.dm_channel_id
          FROM delivery_channels dc
          JOIN platform_identities pi ON pi.id = dc.platform_identity_id
          WHERE dc.account_id = $accountId AND dc.role = 'primary' AND dc.state = 'healthy'
            AND pi.unlinked_at IS NULL
          ORDER BY dc.priority, dc.id""".query[DeliveryTarget]()
