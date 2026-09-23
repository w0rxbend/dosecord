package dosecord.core.ports

import java.time.Instant
import java.util.UUID

/** One delivery target of an account: a healthy primary `delivery_channels` row joined to its platform identity
  * (DESIGN.md section 6). `chatId` is the identity's DM channel; it may be unknown until the vendor's first inbound
  * event.
  */
final case class DeliveryTarget(channelId: UUID, vendor: String, chatId: Option[String])

/** `delivery_channels` as the reminder loop and the outbox dispatcher read them (ADR-004: the loop enqueues one outbox
  * row per channel, the `send_key` carries `channelId`; the dispatcher falls back to the next healthy channel on
  * channelFatal or after 5 min unsent, DESIGN.md section 7.6). Broadcast is M5.2.
  */
trait DeliveryChannelRepository:

  /** Healthy primary channels with a linked identity, ordered by priority. */
  def activePrimaryChannels(accountId: UUID): List[DeliveryTarget]

  /** Marks a channel dead after a `channelFatal` error (DESIGN.md section 7.6): its dispatches stop and the account's
    * rows fall back to the next healthy channel.
    */
  def markDead(channelId: UUID, error: String, now: Instant): Unit

  /** The next healthy channel of the account for fallback (`delivery_channels` ordered by priority, `off` excluded),
    * never the failed one; None when the account has no other usable channel.
    */
  def fallbackChannel(accountId: UUID, excludeChannelId: UUID): Option[DeliveryTarget]

  /** One channel by id (the dispatcher's renderer resolves the chat of a row — including fallback rows, whose channel
    * is not a primary — through it).
    */
  def byId(channelId: UUID): Option[DeliveryTarget]
