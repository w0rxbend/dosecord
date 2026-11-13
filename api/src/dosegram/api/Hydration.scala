package dosegram.api

import dosegram.core.application.hydration.LogWater
import dosegram.core.application.hydration.WaterDayView
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.WaterCopy
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DaySeries
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.util.UUID

/** The hydration resources (ARCHITECTURE section 9.5): entries, the user's own goal and quick-add amounts, and the days
  * of a range. Every call goes through the hydration use cases, which refuse without the `hydration` consent, so a
  * route neither shows nor stores anything before it (API-19). Entries are never updated: a delete voids one.
  */
final class Hydration(uow: UnitOfWork, tracking: TrackingUseCases):
  import Hydration.*

  /** The live entries of a range of local days, oldest first; without a range, those of today. */
  def entries(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, HydrationEntries] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.single(today.date))
      yield HydrationEntries(range.start, range.end, tx.tracking.hydration.listLive(accountId, range).map(entryView))

  /** Records a drink of now. `key` is the idempotency key of the request: the same key records once (MA-HYD-1). */
  def log(accountId: UUID, key: UUID, input: HydrationEntryInput): Either[ApiFailure, HydrationLogged] =
    for
      amount <- WaterAmount.ofMillilitres(input.amountMl).left.map(_ => ApiFailure.invalid("amountMl", "out_of_range"))
      command = LogWater(accountId, amount, EntryDay.Today, EntrySource.MiniApp, s"api:$key")
      logged <- uow.transaction(tracking.hydration.log(_, command)).left.map(Refusals.of)
    yield HydrationLogged(entryView(logged.entry), dayView(logged.day), logged.goalReachedNow)

  /** Voids an entry of any age and answers with its day. A second delete changes nothing and answers the same. */
  def remove(accountId: UUID, entryId: UUID): Either[ApiFailure, HydrationDayView] =
    uow
      .transaction(tracking.hydration.delete(_, accountId, entryId))
      .left
      .map(Refusals.of)
      .map(removed => dayView(removed.day))

  def settings(accountId: UUID): Either[ApiFailure, HydrationSettings] = uow.transaction(settingsIn(_, accountId))

  /** Replaces the goal and the quick-add amounts. Both are checked before either is written. */
  def saveSettings(
      accountId: UUID,
      version: Long,
      input: HydrationSettingsInput
  ): Either[ApiFailure, HydrationSettings] =
    uow.transaction: tx =>
      for
        current <- settingsIn(tx, accountId)
        _ <- Either.cond(current.version == version, (), ApiFailure.versionConflict())
        _ <- validated(input)
        _ <- writeGoal(tx, accountId, current, input)
        _ <- tracking.hydration.setPresets(tx, accountId, input.presetsMl).left.map(Refusals.of)
        saved <- settingsIn(tx, accountId)
      yield saved

  /** Today, the quick-add amounts and the days of a range as a chart; without a range, the last seven days. */
  def summary(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, HydrationSummary] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.endingOn(today.date, DefaultSummaryDays))
        days <- tracking.stats.hydrationCalendar(tx, accountId, range).left.map(Refusals.of)
        presets <- tracking.hydration.presets(tx, accountId).left.map(Refusals.of)
      yield
        val chart = DaySeries.of(days, range).shown.map(Charts.waterByDay(_, range))
        HydrationSummary(
          range.start,
          range.end,
          dayView(today),
          presets,
          chart,
          Option.when(chart.isEmpty)(ApiCopy.noDataInPeriod)
        )

  /** Today in the account zone; a refusal here is the consent gate of every read. */
  private def todayOf(tx: Tx, accountId: UUID): Either[ApiFailure, WaterDayView] =
    tracking.hydration.day(tx, accountId, EntryDay.Today).left.map(Refusals.of)

  private def settingsIn(tx: Tx, accountId: UUID): Either[ApiFailure, HydrationSettings] =
    for
      today <- todayOf(tx, accountId)
      presets <- tracking.hydration.presets(tx, accountId).left.map(Refusals.of)
    yield HydrationSettings(presets, Limits, versionOf(today.goalMl, presets), today.goalMl)

  // A goal that did not change is not written again: every write starts a new goal row for today.
  private def writeGoal(
      tx: Tx,
      accountId: UUID,
      current: HydrationSettings,
      input: HydrationSettingsInput
  ): Either[ApiFailure, Unit] =
    if input.goalMl == current.goalMl then Right(())
    else tracking.hydration.setGoal(tx, accountId, input.goalMl).left.map(Refusals.of).map(_ => ())

object Hydration:
  private val DefaultSummaryDays = 7

  val Limits: HydrationLimits = HydrationLimits(
    entryMinMl = HydrationBounds.MinEntryMl,
    entryMaxMl = HydrationBounds.MaxEntryMl,
    goalMinMl = HydrationBounds.MinGoalMl,
    goalMaxMl = HydrationBounds.MaxGoalMl,
    presetsMax = HydrationBounds.MaxPresets,
    entriesPerDayMax = HydrationBounds.MaxEntriesPerDay
  )

  private def versionOf(goalMl: Option[Int], presetsMl: List[Int]): Long =
    ContentVersion.of(goalMl.fold("none")(_.toString), presetsMl.mkString(","))

  private def validated(input: HydrationSettingsInput): Either[ApiFailure, Unit] =
    val presets = input.presetsMl
    val errors = List(
      Option.when(input.goalMl.exists(!HydrationBounds.isGoal(_)))(FieldError("goalMl", "out_of_range")),
      Option.when(presets.isEmpty)(FieldError("presetsMl", "required")),
      Option.when(presets.size > HydrationBounds.MaxPresets)(FieldError("presetsMl", "too_many")),
      Option.when(presets.exists(!HydrationBounds.isEntry(_)))(FieldError("presetsMl", "out_of_range")),
      Option.when(presets.distinct.size != presets.size)(FieldError("presetsMl", "duplicate"))
    ).flatten
    if errors.isEmpty then Right(()) else Left(ApiFailure.invalid(errors))

  def entryView(entry: HydrationEntry): HydrationEntryView =
    HydrationEntryView(
      id = entry.id,
      date = entry.localDate,
      time = ApiCopy.clock(entry.occurredAt, entry.zone),
      occurredAt = entry.occurredAt,
      amountMl = entry.amount.millilitres,
      text = ApiCopy.millilitres(entry.amount.millilitres)
    )

  /** A day in the server's words. A day without an entry says so; it shows no zero total as a figure of its own. */
  def dayView(view: WaterDayView): HydrationDayView =
    val day = view.recorded.getOrElse(HydrationDay(totalMl = 0, entries = 0, goalMl = view.goalMl))
    HydrationDayView(
      date = view.date,
      totalMl = day.totalMl,
      entries = day.entries,
      text = if day.entries == 0 then ApiCopy.nothingLogged else WaterCopy.figures(day),
      goalMl = day.goalMl,
      percent = day.progress.map(_.percent),
      vesselLabel = day.progress.map(WaterCopy.vesselName)
    )
