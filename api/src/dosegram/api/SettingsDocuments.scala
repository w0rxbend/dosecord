package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import sttp.tapir.Schema

// The JSON documents of the settings and consent resources (ARCHITECTURE section 9.5, identity-and-consent and
// schedule-and-reminders). A settings document carries the version its ETag is made of (API-17).

enum ImageThemeChoice(val wire: String):
  case Auto extends ImageThemeChoice("auto")
  case Light extends ImageThemeChoice("light")
  case Dark extends ImageThemeChoice("dark")

object ImageThemeChoice:
  given ReadWriter[ImageThemeChoice] = ApiJson.wireEnum(values, _.wire)
  given Schema[ImageThemeChoice] = Schema.derivedEnumeration[ImageThemeChoice](encode = Some(_.wire))

final case class LanguageChoice(code: String, label: String) derives ReadWriter, Schema

/** `languages` are the shipped locales; `imageTheme` is the theme of the images the bot sends, not of the app (MINI_APP
  * MA-THEME-2).
  */
final case class SettingsView(
    language: String,
    languages: List[LanguageChoice],
    timeZone: String,
    imageTheme: ImageThemeChoice,
    version: Long
) derives ReadWriter,
      Schema

/** The editable set; every member is sent. A new time zone moves the schedules that follow the account. */
final case class SettingsInput(language: String, timeZone: String, imageTheme: ImageThemeChoice)
    derives ReadWriter,
      Schema

/** Local times `HH:MM`; the range may cross midnight (MINI_APP MA-SET-4). */
final case class QuietHoursView(start: String, end: String) derives ReadWriter, Schema

/** `quietHours` is absent when quiet hours are off. `notes` say, in the server's words, what each setting does today.
  */
final case class NotificationSettingsView(
    discreet: Boolean,
    notes: List[String],
    version: Long,
    quietHours: Option[QuietHoursView] = None
) derives ReadWriter,
      Schema

/** The editable set: absent quiet hours turn them off. */
final case class NotificationSettingsInput(discreet: Boolean, quietHours: Option[QuietHoursView] = None)
    derives ReadWriter,
      Schema

/** A zone and its offset from UTC now, as `+02:00`. */
final case class TimeZoneChoice(id: String, offset: String) derives ReadWriter, Schema

final case class TimeZoneList(items: List[TimeZoneChoice]) derives ReadWriter, Schema

final case class TimeZonePreviewRequest(timeZone: String) derives ReadWriter, Schema

/** What a change to `timeZone` would do, in the server's words; nothing is written (MINI_APP MA-SET-3). */
final case class TimeZonePreview(timeZone: String, localTime: String, lines: List[String]) derives ReadWriter, Schema

/** One purpose of PRODUCT_SPEC R-IDC-6 with its state in the ledger. `text` says since when it is granted, or how it is
  * turned on in the chat: the app records no consent (MINI_APP MA-ROLE-2).
  */
final case class ConsentView(
    purpose: String,
    title: String,
    granted: Boolean,
    text: String,
    noticeVersion: Option[Int] = None
) derives ReadWriter,
      Schema

final case class ConsentList(items: List[ConsentView], notes: List[String]) derives ReadWriter, Schema
