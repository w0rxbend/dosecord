package dosegram.core.application.hydration

import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationBounds
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.domain.insights.LocalDays
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentCheck
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.TrackingTx
import dosegram.core.ports.TrackingUnitOfWork

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One request to record a drink. `clientKey` is the idempotency key of the creating request or the callback slot id:
  * the same key records at most once per account.
  */
final case class LogWater(accountId: UUID, amount: WaterAmount, day: EntryDay, source: EntrySource, clientKey: String)

/** Why a hydration request changed nothing. None of these is a comment on the amount the user drinks. */
enum HydrationRefusal:
  /** No active `hydration` consent: nothing is stored and nothing is shown (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing
  case UnknownAccount
  case UnknownEntry

  /** The account already has `limit` entries on that local day (R-HYD-12). */
  case DailyLimitReached(limit: Int)
  case UndoWindowPassed

  /** A goal outside `min` to `max` millilitres (R-HYD-2). */
  case GoalOutOfBounds(min: Int, max: Int)
  case PresetsInvalid

/** A local day of one account as the Today card shows it: `recorded` is absent on a day with no entry. */
final case class WaterDayView(date: LocalDate, zone: ZoneId, recorded: Option[HydrationDay])

/** The outcome of [[HydrationService.log]]. `goalReachedNow` is true only for the entry that first takes the day's
  * total to the goal; `replayed` marks a repeated request that created nothing.
  */
final case class WaterLogged(entry: HydrationEntry, day: WaterDayView, goalReachedNow: Boolean, replayed: Boolean)

/** The outcome of an Undo or a delete: the day after it, and whether this call voided the entry. */
final case class WaterRemoved(day: WaterDayView, changed: Boolean)

/** The hydration use cases (PRODUCT_SPEC section 8.8; ROADMAP G5.3, G5.3b, G5.3c). The chat flows and the Mini App API
  * call these; every write and every read needs the `hydration` consent. The product stores what the user logged and
  * the user's own goal. It computes no goal and comments on no total.
  */
final class HydrationService(uow: TrackingUnitOfWork, consent: ConsentCheck, clock: Clock, ids: IdSource):

  def log(command: LogWater): Either[HydrationRefusal, WaterLogged] =
    consented(command.accountId): (tx, zone) =>
      tx.hydration.findByClientKey(command.accountId, command.clientKey) match
        case Some(existing) => Right(replay(tx, existing))
        case None           => record(tx, zone, command)

  /** Undo on the outcome message: voids the entry while it is younger than 15 minutes. A second Undo changes nothing.
    */
  def undo(accountId: UUID, entryId: UUID): Either[HydrationRefusal, WaterRemoved] =
    remove(accountId, entryId, VoidReason.Undone, Some(HydrationBounds.UndoWindow))

  /** Deletes an entry of any age (the entry list of the Mini App; "void of an entry of today" in chat). */
  def delete(accountId: UUID, entryId: UUID): Either[HydrationRefusal, WaterRemoved] =
    remove(accountId, entryId, VoidReason.Deleted, None)

  /** Sets the user's own goal from today on, or removes it with `None`. Past days keep their goal. */
  def setGoal(accountId: UUID, targetMl: Option[Int]): Either[HydrationRefusal, WaterDayView] =
    if targetMl.exists(!HydrationBounds.isGoal(_)) then
      Left(HydrationRefusal.GoalOutOfBounds(HydrationBounds.MinGoalMl, HydrationBounds.MaxGoalMl))
    else
      consented(accountId): (tx, zone) =>
        val now = clock.now()
        val today = LocalDays.dateOf(now, zone)
        tx.hydration.setGoal(accountId, GoalChange(today, targetMl), now)
        Right(dayView(tx, accountId, today, zone))

  def day(accountId: UUID, day: EntryDay): Either[HydrationRefusal, WaterDayView] =
    consented(accountId): (tx, zone) =>
      val (date, _) = day.resolve(clock.now(), zone)
      Right(dayView(tx, accountId, date, zone))

  /** The quick-add amounts: the user's own, or the defaults until the user sets them (R-HYD-3). */
  def presets(accountId: UUID): Either[HydrationRefusal, List[Int]] =
    consented(accountId): (tx, _) =>
      val own = tx.hydration.presets(accountId)
      Right(if own.isEmpty then HydrationBounds.DefaultPresetsMl else own)

  def setPresets(accountId: UUID, amountsMl: List[Int]): Either[HydrationRefusal, List[Int]] =
    val valid =
      amountsMl.nonEmpty && amountsMl.size <= HydrationBounds.MaxPresets && amountsMl.forall(HydrationBounds.isEntry)
    if !valid then Left(HydrationRefusal.PresetsInvalid)
    else
      consented(accountId): (tx, _) =>
        tx.hydration.replacePresets(accountId, amountsMl)
        Right(amountsMl)

  private def consented[A](accountId: UUID)(
      f: (TrackingTx, ZoneId) => Either[HydrationRefusal, A]
  ): Either[HydrationRefusal, A] =
    if !consent.isGranted(accountId, ConsentPurpose.Hydration) then Left(HydrationRefusal.ConsentMissing)
    else
      uow.transaction: tx =>
        tx.accountZone(accountId) match
          case Some(zone) => f(tx, zone)
          case None       => Left(HydrationRefusal.UnknownAccount)

  private def record(tx: TrackingTx, zone: ZoneId, command: LogWater): Either[HydrationRefusal, WaterLogged] =
    val now = clock.now()
    val (date, occurredAt) = command.day.resolve(now, zone)
    val before = dayView(tx, command.accountId, date, zone)
    if before.recorded.exists(_.entries >= HydrationBounds.MaxEntriesPerDay) then
      Left(HydrationRefusal.DailyLimitReached(HydrationBounds.MaxEntriesPerDay))
    else
      val entry = HydrationEntry(
        id = ids.next(now),
        accountId = command.accountId,
        occurredAt = occurredAt,
        localDate = date,
        zone = zone,
        amount = command.amount,
        source = command.source,
        clientKey = Some(command.clientKey),
        createdAt = now,
        voidedAt = None
      )
      if tx.hydration.insert(entry) then
        val after = dayView(tx, command.accountId, date, zone)
        val reachedNow = !before.recorded.exists(_.isGoalDay) && after.recorded.exists(_.isGoalDay)
        Right(WaterLogged(entry, after, goalReachedNow = reachedNow, replayed = false))
      else
        // A concurrent request with the same client key won the insert: answer with its entry.
        val winner = tx.hydration
          .findByClientKey(command.accountId, command.clientKey)
          .getOrElse(throw IllegalStateException(s"hydration entry ${entry.id} conflicted without a client-key twin"))
        Right(replay(tx, winner))

  private def replay(tx: TrackingTx, existing: HydrationEntry): WaterLogged =
    val day = dayView(tx, existing.accountId, existing.localDate, existing.zone)
    WaterLogged(existing, day, goalReachedNow = false, replayed = true)

  private def remove(
      accountId: UUID,
      entryId: UUID,
      reason: VoidReason,
      window: Option[Duration]
  ): Either[HydrationRefusal, WaterRemoved] =
    consented(accountId): (tx, _) =>
      val now = clock.now()
      tx.hydration.get(accountId, entryId) match
        case None                         => Left(HydrationRefusal.UnknownEntry)
        case Some(entry) if !entry.isLive =>
          Right(WaterRemoved(dayView(tx, accountId, entry.localDate, entry.zone), changed = false))
        case Some(entry) if window.exists(limit => Duration.between(entry.createdAt, now).compareTo(limit) > 0) =>
          Left(HydrationRefusal.UndoWindowPassed)
        case Some(entry) =>
          val changed = tx.hydration.void(accountId, entryId, reason, now)
          Right(WaterRemoved(dayView(tx, accountId, entry.localDate, entry.zone), changed))

  /** The day from its source rows, so the view equals the database after any sequence of entries, voids and Undo. */
  private def dayView(tx: TrackingTx, accountId: UUID, date: LocalDate, zone: ZoneId): WaterDayView =
    val entries = tx.hydration.listLive(accountId, DateRange.single(date))
    val goals = tx.hydration.goals(accountId, date)
    WaterDayView(date, zone, HydrationStats.byDay(entries, goals).get(date))
end HydrationService
