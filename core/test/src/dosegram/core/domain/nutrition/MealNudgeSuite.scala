package dosegram.core.domain.nutrition

import dosegram.core.domain.EntrySource
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.NudgeSlots
import dosegram.core.domain.reminders.PromptPresets

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G8.4: the meal-anchor preset gives one slot per main meal, each slot names its meal by the day profile, and
  * only an entry of that meal answers it.
  */
class MealNudgeSuite extends munit.FunSuite:

  private val zone = ZoneId.of("Europe/Kyiv")
  private val date = LocalDate.parse("2026-11-16")

  private def entry(mealType: MealType, voided: Boolean = false): MealEntry =
    val at = Instant.parse("2026-11-16T05:40:00Z")
    MealEntry(UUID.randomUUID(), UUID.randomUUID(), at, date, zone, mealType, None, EntrySource.Bot, None, None, at,
      Option.when(voided)(at))

  test("each slot of the meal-anchor preset names its meal; a moved anchor moves its slot"):
    val profile = DayProfile.Default.copy(lunch = java.time.LocalTime.of(12, 15))
    val slots = NudgeSlots.on(PromptPresets.MealAnchors.plan, profile, date, zone, seed = 1)
    assertEquals(slots.map(_.slotKey), List("0800", "1215", "1900"))
    assertEquals(slots.map(MealNudge.mealOf(profile, _)), List(Some(MealType.Breakfast), Some(MealType.Lunch), Some(MealType.Dinner)))

  test("only a live entry of the slot's own meal answers it; a snack or another meal does not"):
    assert(MealNudge.isLogged(Some(MealType.Lunch), List(entry(MealType.Lunch))))
    assert(!MealNudge.isLogged(Some(MealType.Lunch), List(entry(MealType.Breakfast), entry(MealType.Snack))))
    assert(!MealNudge.isLogged(Some(MealType.Lunch), List(entry(MealType.Lunch, voided = true))))
    assert(!MealNudge.isLogged(None, List(entry(MealType.Lunch))))
