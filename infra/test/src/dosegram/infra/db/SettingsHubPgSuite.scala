package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.core.application.Application
import dosegram.core.domain.QuietHours
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.copy.SettingsCopy
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.WorkHours
import dosegram.core.ports.ImageTheme
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.sql.Connection
import java.time.Duration
import java.time.LocalTime
import java.util.UUID
import scala.language.implicitConversions

/** The settings hub of `/settings` (supervisor decision, 2026-11-28; BOT_UX section 17) on Testcontainers Postgres 18,
  * from `/start` on the production composition: the day is edited with the use cases of `/reminders`, so both read the
  * same profile; the reports line shows what `/reminders` turned on; and each audited change is one `audit_log` row of
  * codes only.
  */
class SettingsHubPgSuite extends PgSuite:

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  private def settingsAudits(account: UUID): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT channel || ':' || array_to_string(changed_fields, ',') FROM audit_log
            WHERE subject_ref = $account AND object_type = 'settings' ORDER BY occurred_at, id""".query[String]()

  test("the day is picked from short lists in place, and /reminders reads the same profile"):
    withRig("settings-day"): rig =>
      val account = rig.onboard()
      rig.line("/settings")
      rig.tap(SettingsCopy.title, SettingsCopy.DayButton)
      rig.tap(SettingsCopy.dayHelp, "Wake 07:00")
      val wake = rig.tap("Pick a new time", "06:30")
      assert(wake.exists(_.contains(SettingsCopy.changed("Wake", "07:00", "06:30"))), wake.toString)
      rig.tap(SettingsCopy.dayHelp, SettingsCopy.WorkButton)
      rig.tap("Pick new work hours", "Mon to Fri 08:00–16:00")
      rig.tap(SettingsCopy.dayHelp, SettingsCopy.QuietButton)
      val quiet = rig.tap("Pick new quiet hours", "23:00–07:00")
      assert(quiet.exists(_.contains("Quiet hours: 23:00–07:00")), quiet.toString)
      val profile = rig.uow.transaction(tx => Application.reminderSettings(rig.clock).profile(tx, account))
      assertEquals(profile.wake, LocalTime.of(6, 30))
      assertEquals(profile.work, WorkHours(WorkHours.Weekdays, LocalTime.of(8, 0), LocalTime.of(16, 0)))
      assertEquals(profile.quiet, QuietHours(HhMm.unsafe("23:00"), HhMm.unsafe("07:00")))
      // The anchors are the day profile of /reminders, which audits none of them; the quiet hours are an account
      // setting, audited as the Mini App audits them.
      assertEquals(settingsAudits(account).filter(_.endsWith("quiet_hours")), List("bot:quiet_hours"))
      val hub = rig.line("/settings").mkString("\n")
      assert(hub.contains("Wake 06:30 · Breakfast 08:00"), hub)
      assert(hub.contains("Work: Mon to Fri 08:00–16:00"), hub)

  test("the reports line shows the morning summary /reminders turned on, and its button opens /reminders in place"):
    withRig("settings-reports"): rig =>
      rig.onboard()
      val before = rig.line("/settings").mkString("\n")
      assert(before.contains("Morning summary: off"), before)
      val reminders = rig.tap(SettingsCopy.title, SettingsCopy.RemindersButton)
      assert(reminders.exists(_.contains(PromptCopy.hubTitle)), reminders.toString)
      rig.tap(PromptCopy.hubTitle, PromptCopy.kindName(PromptKind.DailyReport))
      rig.tap(PromptCopy.choosePreset(PromptKind.DailyReport), PromptCopy.presetName(PromptPresets.morningSummary(8).id))
      rig.tap(PromptCopy.presetName(PromptPresets.morningSummary(8).id), PromptCopy.TurnOn)
      val after = rig.line("/settings").mkString("\n")
      assert(after.contains("Morning summary: on · Weekly summary: off · Monthly summary: off"), after)

  test("each switch is one audit row of codes only, and a repeated tap of an old button writes none"):
    withRig("settings-audit"): rig =>
      val account = rig.onboard()
      rig.line("/settings")
      val discreet = rig.wireOf(SettingsCopy.title, SettingsCopy.DiscreetButton(false))
      // A second apart, so the rows of the same test clock read back in the order of the taps.
      def later[A](tap: => A): A =
        rig.clock.advance(Duration.ofSeconds(1))
        tap
      later(rig.tap(SettingsCopy.title, SettingsCopy.DiscreetButton(false)))
      later(rig.tapWire(discreet, None))
      later(rig.tap(SettingsCopy.title, SettingsCopy.CountsButton(true)))
      later(rig.tap(SettingsCopy.title, SettingsCopy.NotesButton(true)))
      later(rig.tap(SettingsCopy.title, SettingsCopy.ThemeButton(ImageTheme.Auto)))
      val rows = settingsAudits(account).filterNot(_.endsWith("timezone"))
      assertEquals(rows, List("bot:discreet", "bot:counts", "bot:whats_new", "bot:image_theme"))
end SettingsHubPgSuite
