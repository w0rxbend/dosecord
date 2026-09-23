package dosecord.infra.db

import dosecord.core.ports.DeliveryChannelRepository
import dosecord.core.ports.DeliveryTarget

import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** Postgres `delivery_channels` (DESIGN.md section 6): the healthy primary channels the reminder loop enqueues to, the
  * channel-dead marking on `channelFatal`, and the next healthy channel for the dispatcher's fallback.
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

  override def markDead(channelId: UUID, error: String, now: Instant): Unit =
    sql"""UPDATE delivery_channels
          SET state = 'dead', last_error = $error, updated_at = $now
          WHERE id = $channelId""".execute()
    ()

  override def fallbackChannel(accountId: UUID, excludeChannelId: UUID): Option[DeliveryTarget] =
    sql"""SELECT dc.id, pi.vendor, pi.dm_channel_id
          FROM delivery_channels dc
          JOIN platform_identities pi ON pi.id = dc.platform_identity_id
          WHERE dc.account_id = $accountId AND dc.state = 'healthy' AND dc.role <> 'off'
            AND dc.id <> $excludeChannelId AND pi.unlinked_at IS NULL AND pi.dm_channel_id IS NOT NULL
          ORDER BY dc.priority, dc.id
          LIMIT 1""".queryOne[DeliveryTarget]()

  override def byId(channelId: UUID): Option[DeliveryTarget] =
    sql"""SELECT dc.id, pi.vendor, pi.dm_channel_id
          FROM delivery_channels dc
          JOIN platform_identities pi ON pi.id = dc.platform_identity_id
          WHERE dc.id = $channelId AND pi.unlinked_at IS NULL""".queryOne[DeliveryTarget]()
