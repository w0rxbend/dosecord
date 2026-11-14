package dosegram.core.application.nutrition

import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.LocalDays
import dosegram.core.domain.nutrition.MealBounds
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.Tx

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** What a meal is: its type, its optional words and its time; `at` is absent for "now". */
final case class MealInput(mealType: MealType, text: Option[MealText], at: Option[Instant])

/** One meal to record. `clientKey` makes the same tap or request record at most once. */
final case class LogMeal(accountId: UUID, meal: MealInput, source: EntrySource, clientKey: String)

/** A change of a recorded meal: a new entry that supersedes `mealId` (PRODUCT_SPEC R-NUT-10). */
final case class ChangeMeal(accountId: UUID, mealId: UUID, meal: MealInput, source: EntrySource, clientKey: String)

/** Why a meal request changed nothing. No refusal depends on what was eaten. */
enum MealRefusal:
  /** No active `nutrition` consent: nothing is stored and nothing is shown (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing
  case UnknownAccount
  case UnknownMeal
  case UndoWindowPassed

  /** The meal time is in the future. */
  case InFuture

/** The live meals of one local day of one account, oldest first. */
final case class MealDayView(date: LocalDate, zone: ZoneId, entries: List[MealEntry])

final case class MealRecorded(entry: MealEntry, day: MealDayView, replayed: Boolean)

final case class MealRemoved(day: MealDayView, changed: Boolean)

/** The meal journal (PRODUCT_SPEC section 8.12; ROADMAP G8.1, G8.2). Every write and every read needs the `nutrition`
  * consent. Nothing here reads the words of a meal: they are stored and shown to their owner, never analysed, and no
  * energy or nutrient value is computed (R-NUT-5). Every use case runs on the caller's transaction.
  */
final class NutritionService(clock: Clock, ids: IdSource):

  def log(tx: Tx, command: LogMeal): Either[MealRefusal, MealRecorded] =
    consented(tx, command.accountId): zone =>
      tx.tracking.meals.findByClientKey(command.accountId, command.clientKey) match
        case Some(existing) => Right(recorded(tx, existing, replayed = true))
        case None           =>
          entryOf(command.accountId, command.meal, zone, command.source, command.clientKey, supersedes = None)
            .map(store(tx, _))

  /** Replaces a live meal by a changed copy. Entries are immutable, so the old row is voided as superseded. */
  def change(tx: Tx, command: ChangeMeal): Either[MealRefusal, MealRecorded] =
    consented(tx, command.accountId): zone =>
      tx.tracking.meals.findByClientKey(command.accountId, command.clientKey) match
        case Some(existing) => Right(recorded(tx, existing, replayed = true))
        case None           =>
          tx.tracking.meals.get(command.accountId, command.mealId).filter(_.isLive) match
            case None           => Left(MealRefusal.UnknownMeal)
            case Some(original) =>
              val at = command.meal.at.orElse(Some(original.occurredAt))
              entryOf(
                command.accountId,
                command.meal.copy(at = at),
                zone,
                command.source,
                command.clientKey,
                Some(original.id)
              )
                .flatMap(supersede(tx, original, _))

  private def supersede(tx: Tx, original: MealEntry, amended: MealEntry): Either[MealRefusal, MealRecorded] =
    // The void is the fence: of two concurrent changes of one meal, only the one that voids it may replace it.
    if !tx.tracking.meals.void(original.accountId, original.id, VoidReason.Superseded, amended.createdAt) then
      Left(MealRefusal.UnknownMeal)
    else Right(store(tx, amended))

  /** Undo on the outcome message: voids the meal while it is younger than 15 minutes (BOT_UX BUX-UNDO-01). */
  def undo(tx: Tx, accountId: UUID, mealId: UUID): Either[MealRefusal, MealRemoved] =
    remove(tx, accountId, mealId, VoidReason.Undone, Some(MealBounds.UndoWindow))

  /** Deletes a meal of any age (R-NUT-10). A second delete changes nothing. */
  def delete(tx: Tx, accountId: UUID, mealId: UUID): Either[MealRefusal, MealRemoved] =
    remove(tx, accountId, mealId, VoidReason.Deleted, None)

  private def remove(
      tx: Tx,
      accountId: UUID,
      mealId: UUID,
      reason: VoidReason,
      window: Option[Duration]
  ): Either[MealRefusal, MealRemoved] =
    consented(tx, accountId): _ =>
      val now = clock.now()
      tx.tracking.meals.get(accountId, mealId) match
        case None                       => Left(MealRefusal.UnknownMeal)
        case Some(meal) if !meal.isLive => Right(MealRemoved(dayView(tx, accountId, meal.localDate, meal.zone), false))
        case Some(meal) if window.exists(limit => Duration.between(meal.createdAt, now).compareTo(limit) > 0) =>
          Left(MealRefusal.UndoWindowPassed)
        case Some(meal) =>
          val changed = tx.tracking.meals.void(accountId, mealId, reason, now)
          Right(MealRemoved(dayView(tx, accountId, meal.localDate, meal.zone), changed))

  def get(tx: Tx, accountId: UUID, mealId: UUID): Either[MealRefusal, MealEntry] =
    consented(tx, accountId)(_ =>
      tx.tracking.meals.get(accountId, mealId).filter(_.isLive).toRight(MealRefusal.UnknownMeal)
    )

  /** The meals of today in the account zone. */
  def today(tx: Tx, accountId: UUID): Either[MealRefusal, MealDayView] =
    consented(tx, accountId)(zone => Right(dayView(tx, accountId, LocalDays.dateOf(clock.now(), zone), zone)))

  /** The live meals of a range of local days, oldest first. */
  def list(tx: Tx, accountId: UUID, range: DateRange): Either[MealRefusal, List[MealEntry]] =
    consented(tx, accountId)(_ => Right(tx.tracking.meals.listLive(accountId, range)))

  /** Up to five distinct words saved before for a meal type (MINI_APP S-08 behaviour 2). */
  def recent(tx: Tx, accountId: UUID, mealType: MealType): Either[MealRefusal, List[MealText]] =
    consented(tx, accountId)(_ =>
      Right(tx.tracking.meals.recentTexts(accountId, mealType, NutritionService.RecentLimit))
    )

  /** The user's planned main meals, or the default plan of breakfast, lunch and dinner. */
  def plan(tx: Tx, accountId: UUID): Either[MealRefusal, MealPlan] =
    consented(tx, accountId)(_ => Right(tx.tracking.meals.plan(accountId).getOrElse(MealPlan.Default)))

  def setPlan(tx: Tx, accountId: UUID, plan: MealPlan): Either[MealRefusal, MealPlan] =
    consented(tx, accountId): _ =>
      tx.tracking.meals.savePlan(accountId, plan, clock.now())
      Right(plan)

  private def entryOf(
      accountId: UUID,
      meal: MealInput,
      zone: ZoneId,
      source: EntrySource,
      clientKey: String,
      supersedes: Option[UUID]
  ): Either[MealRefusal, MealEntry] =
    val now = clock.now()
    val at = meal.at.getOrElse(now)
    if at.isAfter(now.plus(MealBounds.FutureSkew)) then Left(MealRefusal.InFuture)
    else
      Right(
        MealEntry(
          id = ids.next(now),
          accountId = accountId,
          occurredAt = at,
          localDate = LocalDays.dateOf(at, zone),
          zone = zone,
          mealType = meal.mealType,
          text = meal.text,
          source = source,
          clientKey = Some(clientKey),
          supersedesId = supersedes,
          createdAt = now,
          voidedAt = None
        )
      )

  private def consented[A](tx: Tx, accountId: UUID)(f: ZoneId => Either[MealRefusal, A]): Either[MealRefusal, A] =
    if !tx.consents.isGranted(accountId, ConsentPurpose.Nutrition) then Left(MealRefusal.ConsentMissing)
    else
      tx.accounts.timezoneOf(accountId) match
        case Some(zone) => f(ZoneId.of(zone))
        case None       => Left(MealRefusal.UnknownAccount)

  private def store(tx: Tx, entry: MealEntry): MealRecorded =
    if tx.tracking.meals.insert(entry) then recorded(tx, entry, replayed = false)
    else
      // A concurrent request with the same client key won the insert: answer with its entry.
      val winner = entry.clientKey
        .flatMap(tx.tracking.meals.findByClientKey(entry.accountId, _))
        .getOrElse(throw IllegalStateException(s"meal ${entry.id} conflicted without a client-key twin"))
      recorded(tx, winner, replayed = true)

  private def recorded(tx: Tx, entry: MealEntry, replayed: Boolean): MealRecorded =
    MealRecorded(entry, dayView(tx, entry.accountId, entry.localDate, entry.zone), replayed)

  private def dayView(tx: Tx, accountId: UUID, date: LocalDate, zone: ZoneId): MealDayView =
    MealDayView(date, zone, tx.tracking.meals.listLive(accountId, DateRange.single(date)).sortBy(_.occurredAt))
end NutritionService

object NutritionService:
  val RecentLimit: Int = 5
