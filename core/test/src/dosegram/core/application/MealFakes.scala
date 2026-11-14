package dosegram.core.application

import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.RollupDomain
import dosegram.core.domain.insights.RollupKey
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.MealRepository

import java.time.Instant
import java.util.UUID
import scala.collection.mutable

/** The meal tables in memory. It keeps the two behaviours of the database the use cases rely on: a client key is
  * unique per account, and every write queues the day's `nutrition` rollup (the trigger of V4).
  */
final class InMemoryMeals(dirty: mutable.LinkedHashSet[RollupKey]) extends MealRepository:
  val entries: mutable.LinkedHashMap[UUID, MealEntry] = mutable.LinkedHashMap.empty
  private val plans: mutable.Map[UUID, MealPlan] = mutable.Map.empty

  def live: List[MealEntry] = entries.values.filter(_.isLive).toList

  override def insert(entry: MealEntry): Boolean =
    val taken = entry.clientKey.exists(key => findByClientKey(entry.accountId, key).isDefined)
    if !taken then
      entries(entry.id) = entry
      dirty += RollupKey(entry.accountId, entry.localDate, RollupDomain.Nutrition)
    !taken

  override def get(accountId: UUID, id: UUID): Option[MealEntry] = entries.get(id).filter(_.accountId == accountId)

  override def findByClientKey(accountId: UUID, clientKey: String): Option[MealEntry] =
    entries.values.find(entry => entry.accountId == accountId && entry.clientKey.contains(clientKey))

  override def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean =
    get(accountId, id).filter(_.isLive) match
      case Some(entry) =>
        entries(id) = entry.copy(voidedAt = Some(now))
        dirty += RollupKey(accountId, entry.localDate, RollupDomain.Nutrition)
        true
      case None => false

  override def listLive(accountId: UUID, range: DateRange): List[MealEntry] =
    entries.values
      .filter(e => e.accountId == accountId && e.isLive && range.contains(e.localDate))
      .toList
      .sortBy(e => (e.occurredAt, e.id))

  override def recentTexts(accountId: UUID, mealType: MealType, limit: Int): List[MealText] =
    entries.values
      .filter(e => e.accountId == accountId && e.isLive && e.mealType == mealType)
      .toList
      .sortBy(_.occurredAt)
      .reverse
      .flatMap(_.text)
      .distinctBy(_.value)
      .take(limit)

  override def plan(accountId: UUID): Option[MealPlan] = plans.get(accountId)

  override def savePlan(accountId: UUID, plan: MealPlan, now: Instant): Unit = plans(accountId) = plan
