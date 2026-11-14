package dosegram.render

import dosegram.render.card.DayCard
import dosegram.render.card.DayData
import dosegram.render.card.FoodDayCard
import dosegram.render.card.FoodDayData
import dosegram.render.card.MainMeal
import dosegram.render.card.MealProgress
import dosegram.render.card.Privacy
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.tokens.Theme

import java.time.LocalDate
import java.time.LocalTime

/** IMG-09 and the food row of IMG-03 (ROADMAP G8.3): a logged slot is filled and an open one outlined, the caption
  * lists every meal with its time, and a day without planned meals is a count, never a rate.
  */
class FoodCardSuite extends munit.FunSuite:

  private val date = LocalDate.of(2026, 11, 12)
  private val named = Samples.context(Theme.Light, Privacy.Aggregate)

  private def roles(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Mark(meta, _) => meta.role }

  private def texts(nodes: Vector[Node]): Vector[String] =
    Node.flatten(nodes).collect { case Node.Text(content, _, _, _, _, _) => content }

  test("a logged slot is filled and an open slot outlined; snacks fill their slot when there is one"):
    val food = FoodDayData(date, Map(MainMeal.Breakfast -> LocalTime.of(8, 10)), snacks = 2, MealProgress(1, 3))
    val card = FoodDayCard.render(food, named)
    val marks = roles(card.scene.nodes)
    assertEquals(marks.count(_ == "food.slot.logged"), 2)
    assertEquals(marks.count(_ == "food.slot.open"), 2)
    assert(texts(card.scene.nodes).contains("08:10"))
    assertEquals(card.text.full, "Food on Thu 12 Nov: breakfast 08:10, 2 snacks. 1 of 3 meals, 2 snacks.")

  test("an empty day draws four open slots and says nothing is logged, without a judgement"):
    val card = FoodDayCard.render(FoodDayData(date, Map.empty, 0, MealProgress(0, 3)), named)
    assertEquals(roles(card.scene.nodes).count(_ == "food.slot.open"), 4)
    assertEquals(card.text.full, "Food on Thu 12 Nov: no meal logged yet. 0 of 3 meals.")

  test("with no planned meal the card and the daily summary state a count, never a rate"):
    val food = FoodDayData(date, Map(MainMeal.Lunch -> LocalTime.of(12, 30)), 0, MealProgress(0, 0))
    assert(FoodDayCard.render(food, named).text.full.endsWith("1 meal logged."))
    val day = DayCard.render(DayData(date, None, None, None, Some(MealProgress(2, 0)), None), named)
    assert(day.text.full.contains("Food: 2 meals logged."), day.text.full)
    assert(texts(day.scene.nodes).contains("2 meals logged"))

  test("the Ukrainian headline and the sentence it ends the text with start with a capital"):
    val ukrainian = Samples.context(Theme.Light, Privacy.Aggregate, UkrainianImageCopy)
    val counted = FoodDayData(date, Map(MainMeal.Lunch -> LocalTime.of(12, 30)), 0, MealProgress(0, 0))
    val card = FoodDayCard.render(counted, ukrainian)
    assert(texts(card.scene.nodes).contains("Записано 1 прийом їжі"), texts(card.scene.nodes).toString)
    assert(card.text.full.endsWith(": обід 12:30. Записано 1 прийом їжі."), card.text.full)
    val snacks = FoodDayCard.render(counted.copy(mains = Map.empty, snacks = 1), ukrainian)
    assert(texts(snacks.scene.nodes).exists(_.startsWith("Прийомів їжі не записано")), texts(snacks.scene.nodes).toString)

  test("in discreet mode the caption is the generic line and the image goes behind a spoiler"):
    val food = FoodDayData(date, Map(MainMeal.Dinner -> LocalTime.of(19, 40)), 0, MealProgress(1, 3))
    val card = FoodDayCard.render(food, Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals(card.text.caption, "Food today.")
    assert(card.hasSpoiler)
