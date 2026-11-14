package dosegram.api

import dosegram.contracts.HhMm
import dosegram.core.domain.QuietHours
import dosegram.core.domain.copy.Formats
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import dosegram.core.scheduling.ScheduleLifecycle

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The account's own settings and consents (ARCHITECTURE section 9.5, identity-and-consent and schedule-and-reminders):
  * `GET`/`PATCH /settings`, `GET`/`PATCH /notification-settings`, `GET /time-zones`, `POST /time-zone-previews` and
  * `GET /consents`. A settings document carries a version made of its content (API-17), so an edit from the chat in
  * between is a version conflict. The app records no consent of its own (MINI_APP MA-ROLE-2): consents are read from
  * the ledger here and changed in the chat.
  */
final class AccountSettingsApi(uow: UnitOfWork, clock: Clock):
  import AccountSettingsApi.*

  private val lifecycle = ScheduleLifecycle(uow, clock)

  def settings(accountId: UUID): Either[ApiFailure, SettingsView] =
    uow.transaction(stored(_, accountId).map(settingsView))

  /** Replaces the editable set. A new time zone moves the schedules that follow the account, in the same transaction.
    */
  def saveSettings(accountId: UUID, version: Long, input: SettingsInput): Either[ApiFailure, SettingsView] =
    uow.transaction: tx =>
      for
        current <- stored(tx, accountId)
        _ <- Either.cond(settingsView(current).version == version, (), ApiFailure.versionConflict())
        zone <- validated(input)
      yield
        val now = clock.now()
        tx.accounts.saveSettings(
          accountId,
          current.settings.copy(locale = Some(input.language), imageTheme = themeOf(input.imageTheme)),
          now
        )
        if zone.getId != current.zone then
          tx.accounts.setTimezone(accountId, zone.getId, now)
          lifecycle.rezoneFollowingSchedulesInTx(tx, accountId, zone, now)
        settingsView(Stored(zone.getId, tx.accounts.settingsOf(accountId).get))

  def notifications(accountId: UUID): Either[ApiFailure, NotificationSettingsView] =
    uow.transaction(stored(_, accountId).map(notificationView))

  def saveNotifications(
      accountId: UUID,
      version: Long,
      input: NotificationSettingsInput
  ): Either[ApiFailure, NotificationSettingsView] =
    uow.transaction: tx =>
      for
        current <- stored(tx, accountId)
        _ <- Either.cond(notificationView(current).version == version, (), ApiFailure.versionConflict())
        quietHours <- input.quietHours.fold(Right(None))(quietHoursOf(_).map(Some(_)))
      yield
        val settings = current.settings.copy(quietHours = quietHours, isDiscreet = input.discreet)
        tx.accounts.saveSettings(accountId, settings, clock.now())
        notificationView(current.copy(settings = settings))

  /** Every region zone of the platform's database with its offset from UTC now, by name. */
  def timeZones: TimeZoneList =
    val now = clock.now()
    TimeZoneList(RegionZones.map(id => TimeZoneChoice(id, offsetOf(ZoneId.of(id), now))))

  /** What a change of the time zone would do to the schedules (MINI_APP MA-SET-3). Nothing is written. Schedules are
    * named only with the `medication` consent: without it the preview says nothing about them.
    */
  def preview(accountId: UUID, request: TimeZonePreviewRequest): Either[ApiFailure, TimeZonePreview] =
    for
      zone <- zoneOf(request.timeZone)
      lines <- uow.transaction: tx =>
        stored(tx, accountId).map: _ =>
          if !tx.consents.isGranted(accountId, ConsentPurpose.Medication) then List(ApiCopy.noSchedulesMove)
          else scheduleLines(tx, accountId)
    yield TimeZonePreview(zone.getId, ApiCopy.timeThere(ClockTime.format(clock.now().atZone(zone))), lines)

  def consents(accountId: UUID): Either[ApiFailure, ConsentList] =
    uow.transaction: tx =>
      stored(tx, accountId).map: _ =>
        val granted = tx.consents.granted(accountId).map(consent => consent.purpose -> consent).toMap
        val items = ConsentPurpose.values.toList.map: purpose =>
          granted.get(purpose) match
            case Some(consent) =>
              ConsentView(
                purpose.id,
                titleOf(purpose),
                granted = true,
                s"On since ${Since.format(consent.grantedAt.atZone(ZoneId.of("UTC")))}.",
                Some(consent.policyVersion)
              )
            case None => ConsentView(purpose.id, titleOf(purpose), granted = false, ApiCopy.consentOff(purpose))
        ConsentList(items, List(ApiCopy.withdrawInChat, ApiCopy.chatIsNotEncrypted))

  private def scheduleLines(tx: Tx, accountId: UUID): List[String] =
    val following = tx.schedules.listFollowingForTzChange(accountId).count(_.status != ScheduleStatus.Archived)
    val fixed = for
      medication <- tx.medications.listAllForAccount(accountId)
      schedule <- tx.schedules.listForMedication(medication.id)
      if schedule.status == ScheduleStatus.Active && !schedule.tzFollowsUser
    yield ApiCopy.scheduleStays(medication.name, schedule.tz)
    val follow = Option.when(following > 0)(ApiCopy.schedulesFollow(following))
    val lines = follow.toList ++ fixed
    if lines.isEmpty then List(ApiCopy.noSchedulesMove) else lines

  private def stored(tx: Tx, accountId: UUID): Either[ApiFailure, Stored] =
    for
      zone <- tx.accounts.timezoneOf(accountId).toRight(ApiFailure.notFound())
      settings <- tx.accounts.settingsOf(accountId).toRight(ApiFailure.notFound())
    yield Stored(zone, settings)

object AccountSettingsApi:

  /** The locales the product ships (MINI_APP section 5.7), each named in itself (ROADMAP G2.22). */
  val Languages: List[LanguageChoice] = List(LanguageChoice("en", "English"), LanguageChoice("uk", "Українська"))
  private val DefaultLanguage = "en"

  private val ClockTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
  private def Since = Formats.day

  /** The zones a person lives in: `Area/City` names, without the `Etc/` and `SystemV/` aliases. */
  val RegionZones: List[String] =
    ZoneId.getAvailableZoneIds.asScala.toList
      .filter(id => id.contains('/') && !id.startsWith("Etc/") && !id.startsWith("SystemV/"))
      .sorted

  private final case class Stored(zone: String, settings: AccountSettings)

  private def settingsView(stored: Stored): SettingsView =
    val language = stored.settings.locale.filter(code => Languages.exists(_.code == code)).getOrElse(DefaultLanguage)
    val theme = choiceOf(stored.settings.imageTheme)
    SettingsView(language, Languages, stored.zone, theme, ContentVersion.of(language, stored.zone, theme.wire))

  private def notificationView(stored: Stored): NotificationSettingsView =
    val quiet = stored.settings.quietHours.map(hours => QuietHoursView(hours.start.value, hours.end.value))
    NotificationSettingsView(
      discreet = stored.settings.isDiscreet,
      notes = List(ApiCopy.quietHoursNote, ApiCopy.discreetNote),
      version = ContentVersion.of(stored.settings.isDiscreet.toString, quiet.fold("off")(q => s"${q.start}-${q.end}")),
      quietHours = quiet
    )

  private def validated(input: SettingsInput): Either[ApiFailure, ZoneId] =
    val language = Option.when(!Languages.exists(_.code == input.language))(FieldError("language", "unsupported"))
    val zone = zoneOf(input.timeZone)
    val errors = language.toList ++ zone.left.toOption.toList.flatMap(_.problem.errors)
    if errors.isEmpty then zone else Left(ApiFailure.invalid(errors))

  private def zoneOf(id: String): Either[ApiFailure, ZoneId] =
    Option
      .when(RegionZones.contains(id))(id)
      .flatMap(valid => Try(ZoneId.of(valid)).toOption)
      .toRight(ApiFailure.invalid("timeZone", "unknown"))

  /** MINI_APP MA-SET-4: a range may cross midnight; equal ends are refused. */
  private def quietHoursOf(view: QuietHoursView): Either[ApiFailure, QuietHours] =
    val start = HhMm.parse(view.start).left.map(_ => FieldError("quietHours.start", "invalid"))
    val end = HhMm.parse(view.end).left.map(_ => FieldError("quietHours.end", "invalid"))
    (start, end) match
      case (Right(from), Right(to)) if from == to => Left(ApiFailure.invalid("quietHours", "quiet_hours_empty"))
      case (Right(from), Right(to))               => Right(QuietHours(from, to))
      case _                                      => Left(ApiFailure.invalid(List(start, end).flatMap(_.left.toOption)))

  private def offsetOf(zone: ZoneId, now: Instant): String =
    val offset = zone.getRules.getOffset(now).getId
    if offset == "Z" then "+00:00" else offset

  private def themeOf(choice: ImageThemeChoice): ImageTheme = choice match
    case ImageThemeChoice.Auto  => ImageTheme.Auto
    case ImageThemeChoice.Light => ImageTheme.Light
    case ImageThemeChoice.Dark  => ImageTheme.Dark

  private def choiceOf(theme: ImageTheme): ImageThemeChoice = theme match
    case ImageTheme.Auto  => ImageThemeChoice.Auto
    case ImageTheme.Light => ImageThemeChoice.Light
    case ImageTheme.Dark  => ImageThemeChoice.Dark

  def titleOf(purpose: ConsentPurpose): String = purpose match
    case ConsentPurpose.Medication          => "Medication"
    case ConsentPurpose.Hydration           => "Water"
    case ConsentPurpose.Mood                => "Mood"
    case ConsentPurpose.Sleep               => "Sleep"
    case ConsentPurpose.CrossDomainPatterns => "Comparisons between areas"
    case ConsentPurpose.ImagesInChat        => "Report images in the chat"
    case ConsentPurpose.Nutrition           => "Meals"
end AccountSettingsApi
