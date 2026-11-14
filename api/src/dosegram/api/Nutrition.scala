package dosegram.api

import dosegram.core.application.nutrition.ChangeMeal
import dosegram.core.application.nutrition.LogMeal
import dosegram.core.application.nutrition.MealDayView
import dosegram.core.application.nutrition.MealInput as MealCommand
import dosegram.core.application.nutrition.MealRefusal
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.FoodCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MealDay
import dosegram.core.domain.insights.MealStats
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.util.UUID

/** The meal resources (ARCHITECTURE section 9.5, MINI_APP section 7.8): meals by day, add, change as supersede, remove,
  * the recent descriptions, the regular-meal view of a range and the planned meals. Every call goes through the
  * nutrition use cases, which refuse without the `nutrition` consent (API-19). The description of a meal is returned
  * only to its owner; nothing here reads it to decide what to answer, and no answer carries energy or a target.
  */
final class Nutrition(uow: UnitOfWork, tracking: TrackingUseCases):
  import Nutrition.*

  /** The live meals of a range of local days, oldest first; without a range, those of today. */
  def meals(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, Meals] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.single(today.date))
        meals <- tracking.nutrition.list(tx, accountId, range).left.map(refusal)
      yield Meals(range.start, range.end, meals.map(mealView))

  def meal(accountId: UUID, mealId: UUID): Either[ApiFailure, MealView] =
    uow.transaction(tracking.nutrition.get(_, accountId, mealId)).left.map(refusal).map(mealView)

  /** Records a meal, or with `supersedes` replaces one. `key` is the idempotency key: the same key records once. */
  def record(accountId: UUID, key: UUID, input: MealEntryInput): Either[ApiFailure, MealRecordedView] =
    for
      text <- MealText
        .of(input.description.getOrElse(""))
        .left
        .map(_ => ApiFailure.invalid("description", "text_too_long"))
      command = MealCommand(mealTypeOf(input.mealType), text, input.occurredAt)
      recorded <- uow.transaction: tx =>
        val outcome = input.supersedes match
          case Some(original) =>
            tracking.nutrition.change(tx, ChangeMeal(accountId, original, command, EntrySource.MiniApp, s"api:$key"))
          case None => tracking.nutrition.log(tx, LogMeal(accountId, command, EntrySource.MiniApp, s"api:$key"))
        outcome.left.map(refusal).flatMap(done => dayOf(tx, accountId, done.day).map(day => (done.entry, day)))
    yield MealRecordedView(mealView(recorded._1), recorded._2)

  /** Voids a meal of any age and answers with its day. A second delete changes nothing and answers the same. */
  def remove(accountId: UUID, mealId: UUID): Either[ApiFailure, NutritionDay] =
    uow.transaction: tx =>
      tracking.nutrition
        .delete(tx, accountId, mealId)
        .left
        .map(refusal)
        .flatMap(removed => dayOf(tx, accountId, removed.day))

  def recent(accountId: UUID, mealType: MealKind): Either[ApiFailure, MealRecent] =
    uow
      .transaction(tracking.nutrition.recent(_, accountId, mealTypeOf(mealType)))
      .left
      .map(refusal)
      .map(texts => MealRecent(mealType, texts.map(_.value)))

  /** Today, the days of a range and the weekly line; without a range, the last seven days (MA-FOOD-4). */
  def summary(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, NutritionSummary] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        plan <- tracking.nutrition.plan(tx, accountId).left.map(refusal)
        range <- Ranges.of(from, to, DateRange.endingOn(today.date, DefaultSummaryDays))
        meals <- tracking.nutrition.list(tx, accountId, range).left.map(refusal)
      yield
        val days = MealStats.byDay(meals)
        val period = MealStats.period(days, range)
        NutritionSummary(
          range.start,
          range.end,
          dayView(today.date, MealStats.byDay(today.entries), plan),
          range.dates.map(dayView(_, days, plan)),
          MealType.main.map(meal => NutritionMealDays(kindOf(meal), FoodCopy.label(meal), period.daysPerMeal(meal))),
          FoodCopy.week(period)
        )

  def settings(accountId: UUID): Either[ApiFailure, NutritionSettings] =
    uow.transaction(tracking.nutrition.plan(_, accountId)).left.map(refusal).map(settingsOf)

  def saveSettings(
      accountId: UUID,
      version: Long,
      input: NutritionSettingsInput
  ): Either[ApiFailure, NutritionSettings] =
    uow.transaction: tx =>
      for
        current <- tracking.nutrition.plan(tx, accountId).left.map(refusal).map(settingsOf)
        _ <- Either.cond(current.version == version, (), ApiFailure.versionConflict())
        planned <- plannedOf(input.planned)
        saved <- tracking.nutrition.setPlan(tx, accountId, MealPlan(planned)).left.map(refusal)
      yield settingsOf(saved)

  /** Today in the account zone; a refusal here is the consent gate of every read. */
  private def todayOf(tx: Tx, accountId: UUID): Either[ApiFailure, MealDayView] =
    tracking.nutrition.today(tx, accountId).left.map(refusal)

  private def dayOf(tx: Tx, accountId: UUID, day: MealDayView): Either[ApiFailure, NutritionDay] =
    tracking.nutrition
      .plan(tx, accountId)
      .left
      .map(refusal)
      .map(plan => dayView(day.date, MealStats.byDay(day.entries), plan))

object Nutrition:
  private val DefaultSummaryDays = 7

  def mealTypeOf(kind: MealKind): MealType = MealType.fromOrdinal(kind.ordinal)

  def kindOf(mealType: MealType): MealKind = MealKind.fromOrdinal(mealType.ordinal)

  private def plannedOf(kinds: List[MealKind]): Either[ApiFailure, Set[MealType]] =
    val planned = kinds.map(mealTypeOf).toSet
    Either.cond(planned.forall(_.isMain), planned, ApiFailure.invalid("planned", "snack_not_planned"))

  private def settingsOf(plan: MealPlan): NutritionSettings =
    val planned = MealType.main.filter(plan.planned)
    NutritionSettings(ContentVersion.of(planned.map(_.dbValue)*), planned.map(kindOf))

  def refusal(refusal: MealRefusal): ApiFailure = refusal match
    case MealRefusal.ConsentMissing                           => ApiFailure.consentRequired(ConsentPurpose.Nutrition)
    case MealRefusal.UnknownAccount | MealRefusal.UnknownMeal => ApiFailure.notFound()
    // The API deletes a meal of any age; only the chat's Undo has a window.
    case MealRefusal.UndoWindowPassed => ApiFailure.notFound()
    case MealRefusal.InFuture         => ApiFailure.invalid("occurredAt", "in_future")

  def mealView(entry: MealEntry): MealView =
    val time = ApiCopy.clock(entry.occurredAt, entry.zone)
    val label = FoodCopy.label(entry.mealType)
    MealView(
      id = entry.id,
      date = entry.localDate,
      time = time,
      occurredAt = entry.occurredAt,
      mealType = kindOf(entry.mealType),
      label = label,
      text = entry.text.fold(s"$label at $time")(words => s"$label at $time: ${words.value}"),
      description = entry.text.map(_.value)
    )

  private def dayView(date: LocalDate, days: Map[LocalDate, MealDay], plan: MealPlan): NutritionDay =
    val recorded = days.get(date)
    val view = MealStats.regular(date, recorded, plan)
    NutritionDay(
      date = date,
      label = ApiCopy.weekday(date),
      text = s"${ApiCopy.dayLabel(date)}: ${FoodCopy.day(view)}",
      meals = recorded.fold(0)(_.meals),
      snacks = view.snacks,
      planned = plan.planned.size,
      plannedLogged = view.plannedLogged,
      logged = MealType.main.filter(view.logged).map(kindOf),
      notLogged = view.notLogged.map(kindOf)
    )
