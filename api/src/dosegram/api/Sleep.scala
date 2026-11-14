package dosegram.api

import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepInput
import dosegram.core.application.sleep.SleepRefusal
import dosegram.core.application.sleep.SleepStatus
import dosegram.core.application.sleep.WokeUp
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.StatsCopy
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DaySeries
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.insights.SleepNight
import dosegram.core.domain.insights.SleepStats
import dosegram.core.domain.sleep.SleepBounds
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.util.UUID

/** The sleep resources (ARCHITECTURE section 9.5, MINI_APP section 7.10): episodes, the two check-ins, the user's own
  * goal and the days of a range. Every call goes through the sleep use cases, which refuse without the `sleep` consent
  * (API-19). Episodes are never updated: a delete voids one. No answer depends on how long or how well the user slept.
  */
final class Sleep(uow: UnitOfWork, tracking: TrackingUseCases, clock: Clock):
  import Sleep.*

  /** The live episodes whose sleep day is in a range, by wake instant; without a range, those of the last 7 days. */
  def episodes(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, SleepEpisodes] =
    uow.transaction: tx =>
      for
        status <- statusOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.endingOn(today(status), DefaultSummaryDays))
      yield SleepEpisodes(range.start, range.end, tx.tracking.sleep.liveEpisodes(accountId, range).map(episodeView))

  /** Records a night or a nap entered after the fact. `key` is the idempotency key: the same key records once. */
  def enter(accountId: UUID, key: UUID, input: SleepEpisodeInput): Either[ApiFailure, SleepEpisodeView] =
    val kind = input.kind match
      case SleepEpisodeKind.Night => SleepKind.Main
      case SleepEpisodeKind.Nap   => SleepKind.Nap
    val command = EnterSleep(inputOf(accountId, key), kind, input.onsetAt, input.wakeAt)
    uow.transaction(tracking.sleep.enter(_, command)).left.map(refusal).map(recorded => episodeView(recorded.episode))

  /** "Going to sleep" or "I'm up" now, and where sleep stands after it. */
  def checkIn(accountId: UUID, key: UUID, input: SleepCheckinInput): Either[ApiFailure, SleepStatusView] =
    uow.transaction: tx =>
      val tapped = input.kind match
        case SleepCheckinKind.GoingToSleep => tracking.sleep.goingToSleep(tx, inputOf(accountId, key)).map(_ => false)
        case SleepCheckinKind.Awake        =>
          tracking.sleep
            .wakeUp(tx, inputOf(accountId, key))
            .map:
              case WokeUp.NeedsBedtime(_) => true
              case WokeUp.Closed(_, _)    => false
      for
        needsBedtime <- tapped.left.map(refusal)
        status <- statusOf(tx, accountId)
      yield statusView(status).copy(needsBedtime = needsBedtime)

  /** Voids an episode of any age and answers where sleep stands. A second delete changes nothing and answers the same.
    */
  def remove(accountId: UUID, episodeId: UUID): Either[ApiFailure, SleepStatusView] =
    uow.transaction: tx =>
      for
        _ <- tracking.sleep.delete(tx, accountId, episodeId).left.map(refusal)
        status <- statusOf(tx, accountId)
      yield statusView(status)

  def settings(accountId: UUID): Either[ApiFailure, SleepSettings] =
    uow.transaction(tx => statusOf(tx, accountId).map(settingsOf))

  def saveSettings(accountId: UUID, version: Long, input: SleepSettingsInput): Either[ApiFailure, SleepSettings] =
    uow.transaction: tx =>
      for
        current <- statusOf(tx, accountId).map(settingsOf)
        _ <- Either.cond(current.version == version, (), ApiFailure.versionConflict())
        _ <-
          if input.goalMinutes == current.goalMinutes then Right(None)
          else tracking.sleep.setGoal(tx, accountId, input.goalMinutes).left.map(refusal)
        saved <- statusOf(tx, accountId).map(settingsOf)
      yield saved

  /** Now, the figures and the nights of a range with their chart; without a range, the last seven days. */
  def summary(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, SleepSummary] =
    uow.transaction: tx =>
      for
        status <- statusOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.endingOn(today(status), DefaultSummaryDays))
        days <- tracking.stats.sleepCalendar(tx, accountId, range).left.map(Refusals.of)
      yield
        val chart = DaySeries.of(days, range).shown.map(Charts.sleepByDay(_, range, status.goalMinutes))
        SleepSummary(
          range.start,
          range.end,
          statusView(status),
          StatsCopy.sleepFigures(SleepStats.period(days, range)),
          range.dates.map(date => summaryDay(date, days.get(date))),
          chart,
          Option.when(chart.isEmpty)(ApiCopy.noDataInPeriod)
        )

  /** Where sleep stands now; a refusal here is the consent gate of every read. */
  private def statusOf(tx: Tx, accountId: UUID): Either[ApiFailure, SleepStatus] =
    tracking.sleep.status(tx, accountId).left.map(refusal)

  private def today(status: SleepStatus): LocalDate = LocalDays.dateOf(clock.now(), status.zone)

object Sleep:
  private val DefaultSummaryDays = 7

  val Limits: SleepLimits = SleepLimits(SleepBounds.MinGoalMinutes, SleepBounds.MaxGoalMinutes)

  /** Where every sleep value comes from (MA-SLEEP-1): every stored episode is the user's own entry. */
  def Source: String = tr("From your check-ins", "З ваших позначок")

  private def inputOf(accountId: UUID, key: UUID): SleepInput = SleepInput(accountId, EntrySource.MiniApp, s"api:$key")

  private def settingsOf(status: SleepStatus): SleepSettings =
    SleepSettings(Limits, ContentVersion.of(status.goalMinutes.fold("none")(_.toString)), status.goalMinutes)

  /** A refusal as the problem document. The field codes are those of the night sheet (MINI_APP MA-SLEEP-6). */
  def refusal(refusal: SleepRefusal): ApiFailure = refusal match
    case SleepRefusal.ConsentMissing                                 => ApiFailure.consentRequired(ConsentPurpose.Sleep)
    case SleepRefusal.UnknownAccount | SleepRefusal.UnknownEpisode   => ApiFailure.notFound()
    case SleepRefusal.UnknownCheckin | SleepRefusal.UndoWindowPassed => ApiFailure.notFound()
    case SleepRefusal.Invalid(SleepInvalid.WakeNotAfterOnset) => ApiFailure.invalid("wakeAt", "wake_before_sleep")
    case SleepRefusal.Invalid(SleepInvalid.InFuture)          => ApiFailure.invalid("wakeAt", "in_future")
    case SleepRefusal.Invalid(SleepInvalid.TooShort(_, _))    => ApiFailure.invalid("wakeAt", "too_short")
    case SleepRefusal.Invalid(SleepInvalid.TooLong(_, _))     => ApiFailure.invalid("wakeAt", "too_long")
    case SleepRefusal.Overlaps(_)                             => ApiFailure.invalid("onsetAt", "overlap")
    case SleepRefusal.NightExists(_)                          => ApiFailure.invalid("wakeAt", "night_exists")
    case SleepRefusal.GoalOutOfBounds                         => ApiFailure.invalid("goalMinutes", "out_of_range")

  def episodeView(episode: SleepEpisode): SleepEpisodeView =
    val minutes = episode.duration.toMinutes.toInt
    val bedtime = ApiCopy.clock(episode.interval.onset.at, episode.interval.onset.zone)
    val wakeTime = ApiCopy.clock(episode.interval.wake.at, episode.interval.wake.zone)
    val kind = if episode.kind == SleepKind.Nap then SleepEpisodeKind.Nap else SleepEpisodeKind.Night
    val noun = if kind == SleepEpisodeKind.Nap then tr("Nap", "Денний сон") else tr("Night", "Ніч")
    SleepEpisodeView(
      id = episode.id,
      kind = kind,
      date = episode.sleepDay,
      onsetAt = episode.interval.onset.at,
      wakeAt = episode.interval.wake.at,
      bedtime = bedtime,
      wakeTime = wakeTime,
      minutes = minutes,
      duration = StatsCopy.duration(minutes),
      source = Source,
      text = tr(
        s"$noun of ${ApiCopy.dayLabel(episode.sleepDay)}: $bedtime to $wakeTime, ${StatsCopy.duration(minutes)}",
        s"$noun, ${ApiCopy.dayLabel(episode.sleepDay)}: з $bedtime до $wakeTime, ${StatsCopy.duration(minutes)}"
      ),
      quality = episode.quality.map(_.step)
    )

  def statusView(status: SleepStatus): SleepStatusView =
    val last = status.latestNight.map(episodeView)
    val open = status.open.map(checkin => ApiCopy.clock(checkin.occurredAt, checkin.zone))
    val text = open match
      case Some(since) => tr(s"Going to sleep recorded at $since.", s"«Лягаю спати» записано о $since.")
      case None        =>
        last.fold(NoNight)(night =>
          tr(
            s"Last: ${night.bedtime} to ${night.wakeTime}, ${night.duration}.",
            s"Остання ніч: з ${night.bedtime} до ${night.wakeTime}, ${night.duration}."
          )
        )
    SleepStatusView(
      text = text,
      openSince = open,
      lastNight = last,
      goalMinutes = status.goalMinutes,
      goalText = status.goalMinutes.map(minutes =>
        tr(s"Your goal: ${StatsCopy.duration(minutes)}", s"Ваша ціль: ${StatsCopy.duration(minutes)}")
      )
    )

  def NoNight: String = tr("No night logged yet", "Ще не записано жодної ночі")

  private def summaryDay(date: LocalDate, night: Option[SleepNight]): SleepSummaryDay =
    val value = night.fold(tr("no night logged", "ніч не записано"))(recorded => StatsCopy.duration(recorded.minutes))
    SleepSummaryDay(date, ApiCopy.weekday(date), s"${ApiCopy.dayLabel(date)}: $value", night.map(_.minutes))
