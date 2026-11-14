package dosegram.core.ports

import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealEntry
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType

import java.time.Instant
import java.util.UUID

/** `meals` and `meal_plans` (ARCHITECTURE section 10.7). Entries are immutable: a change is a new row that supersedes
  * the old one, and the only change to a stored row is its void. Every method is scoped to one account.
  */
trait MealRepository:

  /** Inserts the entry unless the account already has one with the same client key; returns whether a row was written.
    */
  def insert(entry: MealEntry): Boolean

  def get(accountId: UUID, id: UUID): Option[MealEntry]
  def findByClientKey(accountId: UUID, clientKey: String): Option[MealEntry]

  /** Voids a live entry; returns false when it is unknown or already voided. */
  def void(accountId: UUID, id: UUID, reason: VoidReason, now: Instant): Boolean

  /** The live entries with a local date inside the range, oldest first. */
  def listLive(accountId: UUID, range: DateRange): List[MealEntry]

  /** Up to `limit` distinct words of the live entries of one meal type, most recent first (MINI_APP S-08 "Recent"). */
  def recentTexts(accountId: UUID, mealType: MealType, limit: Int): List[MealText]

  /** The account's own plan, or `None` when it never set one. */
  def plan(accountId: UUID): Option[MealPlan]

  def savePlan(accountId: UUID, plan: MealPlan, now: Instant): Unit
