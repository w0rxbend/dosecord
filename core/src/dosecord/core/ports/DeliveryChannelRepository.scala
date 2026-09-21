package dosecord.core.ports

import java.util.UUID

/** One delivery target of an account: a healthy primary `delivery_channels` row joined to its platform identity
  * (DESIGN.md section 6). `chatId` is the identity's DM channel; it may be unknown until the vendor's first inbound
  * event.
  */
final case class DeliveryTarget(channelId: UUID, vendor: String, chatId: Option[String])

/** `delivery_channels` as the reminder loop reads them (ADR-004: the loop enqueues one outbox row per channel, the
  * `send_key` carries `channelId`). Fallback ordering and broadcast are the dispatcher's concern (M1.7, M5.2).
  */
trait DeliveryChannelRepository:

  /** Healthy primary channels with a linked identity, ordered by priority. */
  def activePrimaryChannels(accountId: UUID): List[DeliveryTarget]
