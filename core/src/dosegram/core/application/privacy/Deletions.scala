package dosegram.core.application.privacy

import dosegram.contracts.OutboundMessage
import dosegram.core.application.exports.ExportRequests
import dosegram.core.ports.AuditActor
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.AuditEvent
import dosegram.core.ports.AuditEventType
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.ErasureReceipt
import dosegram.core.ports.IdSource
import dosegram.core.ports.PurgeList
import dosegram.core.ports.Tx

import java.util.UUID

/** The deletions of PRODUCT_SPEC R-IDC-19 and R-IDC-20 (ROADMAP G2.17, G5.7), on the caller's transaction: the chat
  * runs them after the confirming tap, so the deletion, its audit row and the goodbye commit together or not at all.
  */
final class Deletions(clock: Clock, ids: IdSource, purgeList: PurgeList):

  /** The account deletion of ARCHITECTURE SEC-16. The account goes on the purge list first: if the transaction then
    * fails, a restore deletes an account that the user asked to delete, and if the list cannot be written nothing is
    * deleted. Every row of the account goes, its data keys first, reminders and sessions with it, and its audit rows
    * keep the tombstone.
    */
  def account(tx: Tx, accountId: UUID, channel: AuditChannel): ErasureReceipt =
    val now = clock.now()
    purgeList.append(accountId, now)
    val tombstone = ids.next(now)
    val receipt = tx.erasure.eraseAccount(accountId, tombstone, now)
    tx.auditLog.record(
      AuditEvent(
        AuditEventType.AccountDeleteCompleted,
        AuditActor.User(tombstone),
        Some(tombstone),
        "account",
        Some(tombstone),
        "delete",
        channel
      )
    )
    if receipt.dataKeysDestroyed > 0 then
      tx.auditLog.record(
        AuditEvent(
          AuditEventType.DekDestroyed,
          AuditActor.System("erasure"),
          Some(tombstone),
          "data_key",
          None,
          "destroy",
          channel
        )
      )
    receipt

  /** The deletion of one domain (ARCHITECTURE SEC-17): its consent is withdrawn if it was on, `stop` ends what the
    * consent kept running, and its rows and rollups go; no row of another domain is touched.
    */
  def domain(tx: Tx, accountId: UUID, domain: DataDomain, channel: AuditChannel)(
      stop: ConsentPurpose => Unit
  ): ErasureReceipt =
    val now = clock.now()
    if tx.consents.withdraw(accountId, domain.purpose, now) then stop(domain.purpose)
    val receipt = tx.erasure.eraseDomain(accountId, domain, ids.next(now), now)
    tx.auditLog.record(
      AuditEvent
        .byUser(AuditEventType.DomainDeleteCompleted, accountId, "domain", Some(receipt.tombstoneId), "delete", channel)
        .copy(reasonCode = Some(domain.code))
    )
    receipt

/** A deletion the Mini App asks for (MINI_APP section 8.8, MA-SET-7): the app never deletes; it sends the confirmation
  * into the account's private chat, where the tap is the step-up. `false` when the account has no chat to confirm in.
  */
final class DeletionRequests(clock: Clock, ids: IdSource, messages: PrivacyMessages):

  def account(tx: Tx, accountId: UUID): Boolean =
    val now = clock.now()
    val confirmation = Confirmation.shown(now, fromApp = true, domain = None)
    val key = s"app:${ids.next(now)}"
    val sent = send(tx, accountId, messages.accountConfirm(accountId, confirmation, key), key)
    if sent then
      tx.auditLog.record(
        AuditEvent.byUser(AuditEventType.AccountDeleteRequested, accountId, "account", Some(accountId), "delete", App)
      )
      tx.auditLog.record(stepUp(accountId, "account", None))
    sent

  def domain(tx: Tx, accountId: UUID, domain: DataDomain): Boolean =
    val now = clock.now()
    val confirmation = Confirmation.shown(now, fromApp = true, domain = Some(domain))
    val key = s"app:${ids.next(now)}"
    val sent = send(tx, accountId, messages.domainConfirm(accountId, confirmation, domain, key), key)
    if sent then tx.auditLog.record(stepUp(accountId, "domain", Some(domain.code)))
    sent

  private val App = AuditChannel.MiniApp

  private def stepUp(accountId: UUID, objectType: String, reason: Option[String]): AuditEvent =
    AuditEvent
      .byUser(AuditEventType.StepUpRequested, accountId, objectType, None, "delete", App)
      .copy(reasonCode = reason)

  // The export's finished-message kind: the channel renderer sends the payload as it is to the primary chat.
  private def send(tx: Tx, accountId: UUID, message: OutboundMessage, key: String): Boolean =
    ExportRequests.enqueue(tx, ids, accountId, message, s"privacy:$key", clock.now())
