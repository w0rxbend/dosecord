package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.core.ports.SealedField

import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID
import scala.language.implicitConversions

/** One input an account typed while its consent card is open (BOT_UX BUX-CONS-04), in `callback_slots`, sealed with the
  * account's data key. `name` gives every account one slot, so a later hold replaces the earlier one; `payloadKey` is
  * the field of the payload that carries the sealed text. Both are the stored form and must not change for a kind once
  * it has been held.
  */
private[db] final class HeldSlot(name: String, payloadKey: String, keys: KeyService)(using Connection):

  def hold(accountId: UUID, text: String, until: Instant): Unit =
    val id = slot(accountId)
    val sealedText = Base64.getEncoder.encodeToString(keys.sealText(field(accountId, id), text))
    val payload = ujson.write(ujson.Obj(payloadKey -> sealedText))
    sql"""INSERT INTO callback_slots (id, account_id, chat, payload, expires_at)
          VALUES ($id, $accountId, ${Jsonb("{}")}, ${Jsonb(payload)}, $until)
          ON CONFLICT (id) DO UPDATE SET payload = EXCLUDED.payload, expires_at = EXCLUDED.expires_at""".execute()
    ()

  /** Removes the held text of the account and returns it unless it expired before `now`. */
  def take(accountId: UUID, now: Instant): Option[String] =
    val id = slot(accountId)
    given RowMapper[(String, Instant)] = rs => (rs.getString("payload"), rs.instant("expires_at"))
    sql"""DELETE FROM callback_slots WHERE id = $id AND account_id = $accountId
          RETURNING payload, expires_at"""
      .queryOne[(String, Instant)]()
      .filter((_, until) => !until.isBefore(now))
      .map: (payload, _) =>
        keys.openText(field(accountId, id), Base64.getDecoder.decode(ujson.read(payload)(payloadKey).str))

  /** The same id for every hold of the account and kind. */
  private def slot(accountId: UUID): UUID =
    UUID.nameUUIDFromBytes(s"dosegram/$name/$accountId".getBytes(StandardCharsets.UTF_8))

  private def field(accountId: UUID, slot: UUID): SealedField =
    SealedField(KeyOwner.Account(accountId), "callback_slots", "payload", slot.toString)
