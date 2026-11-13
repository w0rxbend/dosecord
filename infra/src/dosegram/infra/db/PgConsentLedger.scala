package dosegram.infra.db

import dosegram.core.ports.ConsentLedger
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.GrantedConsent
import dosegram.core.ports.IdSource

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import scala.language.implicitConversions

/** Postgres consent ledger: `consent_events` (append-only) and its projection `consent_state`, both written on the
  * connection of the surrounding transaction. Every query names the account, so one account never reads the consent of
  * another.
  */
final class PgConsentLedger(conn: Connection, ids: IdSource) extends ConsentLedger:
  private given Connection = conn

  private given RowMapper[GrantedConsent] = rs =>
    val stored = rs.getString("purpose")
    val purpose = ConsentPurpose
      .ofId(stored)
      .getOrElse(
        throw IllegalStateException(s"consent_state.purpose holds '$stored', which the application does not use")
      )
    GrantedConsent(purpose, rs.instant("updated_at"), rs.getInt("policy_version"))

  override def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean =
    sql"""SELECT granted FROM consent_state WHERE account_id = $accountId AND purpose = ${purpose.id}"""
      .queryOne[Boolean]()
      .contains(true)

  override def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, taps: ConsentTaps): Boolean =
    val at = taps.confirmedAt
    val changed =
      sql"""INSERT INTO consent_state (account_id, purpose, granted, policy_version, updated_at)
            VALUES ($accountId, ${purpose.id}, true, ${notice.policyVersion}, $at)
            ON CONFLICT (account_id, purpose) DO UPDATE
              SET granted = true, policy_version = EXCLUDED.policy_version, updated_at = EXCLUDED.updated_at
              WHERE NOT consent_state.granted OR consent_state.policy_version <> EXCLUDED.policy_version"""
        .execute() == 1
    if changed then
      val shown = ShownNotice(hashOf(notice.statement), notice.locale, taps.agreedAt)
      append(ConsentEvent(accountId, purpose, "granted", notice.policyVersion, Some(shown)), at)
    changed

  override def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean =
    val version =
      sql"""UPDATE consent_state SET granted = false, updated_at = $at
            WHERE account_id = $accountId AND purpose = ${purpose.id} AND granted
            RETURNING policy_version""".queryOne[Int]()
    version.foreach(v => append(ConsentEvent(accountId, purpose, "withdrawn", v, shown = None), at))
    version.isDefined

  override def granted(accountId: UUID): List[GrantedConsent] =
    sql"""SELECT purpose, updated_at, policy_version FROM consent_state
          WHERE account_id = $accountId AND granted ORDER BY updated_at, purpose""".query[GrantedConsent]()

  /** What a grant adds to its event: the hash and locale of the statement, and the first of the two taps. */
  private final case class ShownNotice(hash: Array[Byte], locale: String, agreedAt: Instant)

  private final case class ConsentEvent(
      accountId: UUID,
      purpose: ConsentPurpose,
      action: String,
      policyVersion: Int,
      shown: Option[ShownNotice]
  )

  private def append(event: ConsentEvent, at: Instant): Unit =
    sql"""INSERT INTO consent_events (id, account_id, purpose, action, policy_version, notice_hash, locale, agreed_at,
                                      channel, occurred_at)
          VALUES (${ids.next(at)}, ${event.accountId}, ${event.purpose.id}, ${event.action}, ${event.policyVersion},
                  ${event.shown.map(_.hash)}, ${event.shown.map(_.locale)}, ${event.shown.map(
        _.agreedAt
      )}, 'bot', $at)"""
      .execute()
    ()

  private def hashOf(statement: String): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(statement.getBytes(UTF_8))
