package dosegram.core.application.settings

import dosegram.core.ports.AccountSettings
import dosegram.core.ports.AuditChannel
import dosegram.core.ports.Clock
import dosegram.core.ports.SettingsAudit
import dosegram.core.ports.Tx
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.ZoneId
import java.util.UUID

/** What one save changed: the settings before and after, and the audited field codes (empty when nothing changed). */
final case class SettingsSaved(before: AccountSettings, after: AccountSettings, fields: List[String])

/** The one writer of the account's own settings, for the chat's `/settings`, `/language`, the time zone flow and
  * `/reminders`, and the Mini App's settings API alike (supervisor decision, 2026-11-28; BOT_UX section 17): the same
  * stored values, the same field codes and one `audit_log` row per save that changed something. A save that changes
  * nothing writes nothing, so a repeated tap is harmless.
  */
final class AccountSettingsEdits(clock: Clock):

  import AccountSettingsEdits.*

  /** Applies `change` to the stored settings. `None` for an account that does not exist. */
  def save(tx: Tx, account: UUID, channel: AuditChannel)(
      change: AccountSettings => AccountSettings
  ): Option[SettingsSaved] =
    tx.accounts
      .settingsOf(account)
      .map: before =>
        val after = change(before)
        if after != before then tx.accounts.saveSettings(account, after, clock.now())
        audited(tx, account, channel, SettingsSaved(before, after, changedFields(before, after)))

  /** As [[save]], and moves the account to `zone`; the schedules that follow the account move with it, in the same
    * transaction.
    */
  def saveInZone(tx: Tx, account: UUID, channel: AuditChannel, zone: ZoneId, lifecycle: ScheduleLifecycle)(
      change: AccountSettings => AccountSettings
  ): Option[SettingsSaved] =
    for
      zoneBefore <- tx.accounts.timezoneOf(account)
      before <- tx.accounts.settingsOf(account)
    yield
      val now = clock.now()
      val after = change(before)
      val moved = zone.getId != zoneBefore
      if after != before then tx.accounts.saveSettings(account, after, now)
      if moved then
        tx.accounts.setTimezone(account, zone.getId, now)
        lifecycle.rezoneFollowingSchedulesInTx(tx, account, zone, now): Unit
      val fields = changedFields(before, after) ++ Option.when(moved)(TimezoneField)
      audited(tx, account, channel, SettingsSaved(before, after, fields))

  /** "Counts in reports" (PRODUCT_SPEC R-GAM-5). True when the switch changed. */
  def setCounts(tx: Tx, account: UUID, channel: AuditChannel, on: Boolean): Boolean =
    val changed = tx.accounts.gamificationOf(account) != on
    if changed then
      tx.accounts.setGamification(account, on, clock.now())
      tx.auditLog.record(SettingsAudit.changed(account, channel, List(CountsField)))
    changed

  /** The notes after an update (BOT_UX section 6.5). True when the switch changed. */
  def setWhatsNew(tx: Tx, account: UUID, channel: AuditChannel, on: Boolean): Boolean =
    val changed = tx.accounts.whatsNewOf(account).enabled != on
    if changed then
      tx.accounts.setWhatsNewEnabled(account, on, clock.now())
      tx.auditLog.record(SettingsAudit.changed(account, channel, List(WhatsNewField)))
    changed

  private def audited(tx: Tx, account: UUID, channel: AuditChannel, saved: SettingsSaved): SettingsSaved =
    if saved.fields.nonEmpty then tx.auditLog.record(SettingsAudit.changed(account, channel, saved.fields))
    saved

object AccountSettingsEdits:

  val TimezoneField = "timezone"
  val CountsField = "counts"
  val WhatsNewField = "whats_new"

  /** The audit codes of the members that differ, in the order the Mini App has always written them. */
  def changedFields(before: AccountSettings, after: AccountSettings): List[String] =
    List(
      Option.when(before.locale != after.locale)("locale"),
      Option.when(before.imageTheme != after.imageTheme)("image_theme"),
      Option.when(before.animatedMood != after.animatedMood)("animated_mood"),
      Option.when(before.quietHours != after.quietHours)("quiet_hours"),
      Option.when(before.isDiscreet != after.isDiscreet)("discreet")
    ).flatten
