package dosegram.core.application.nutrition

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.ManualClock
import dosegram.core.application.SequentialIds
import dosegram.core.application.TrackingTxDouble
import dosegram.core.domain.EntrySource
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.nutrition.MealPlan
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G8.1 and G8.2, domain part, on the in-memory tables: consent first, one tap, exactly-once by client key,
  * Undo, change as supersede, delete, past days, and the plan.
  */
class NutritionServiceSuite extends munit.FunSuite:

  private val account = UUID.fromString("00000000-0000-7000-8000-0000000000c1")
  private val stranger = UUID.fromString("00000000-0000-7000-8000-0000000000c2")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  // 13:05 in Kyiv.
  private val noon = Instant.parse("2026-06-15T10:05:00Z")
  private val today = LocalDate.parse("2026-06-15")

  private def words(raw: String): Option[MealText] = MealText.of(raw).toOption.flatten

  private final class Rig(consent: FakeConsent = FakeConsent.all(account, stranger)):
    val clock = ManualClock(noon)
    val tables = InMemoryTracking(Map(account -> kyiv, stranger -> kyiv))
    val tx = TrackingTxDouble(tables, consent)
    val service = NutritionService(clock, SequentialIds())

    def command(mealType: MealType, key: String, text: Option[String] = None, at: Option[Instant] = None): LogMeal =
      LogMeal(account, MealInput(mealType, text.flatMap(words), at), EntrySource.Bot, key)

    def log(mealType: MealType, key: String, text: Option[String] = None, at: Option[Instant] = None): MealRecorded =
      service.log(tx, command(mealType, key, text, at)).fold(refusal => fail(s"refused: $refusal"), identity)

  test("no meal is stored or shown before the nutrition consent"):
    val rig = Rig(FakeConsent(Set(account -> ConsentPurpose.Mood)))
    assertEquals(rig.service.log(rig.tx, rig.command(MealType.Lunch, "k1")), Left(MealRefusal.ConsentMissing))
    assertEquals(rig.service.today(rig.tx, account), Left(MealRefusal.ConsentMissing))
    assert(rig.tables.meals.entries.isEmpty)

  test("one tap records one meal at now on the local day, without words (AC-NUT-1)"):
    val rig = Rig()
    val recorded = rig.log(MealType.Lunch, "k1")
    assertEquals(recorded.entry.mealType, MealType.Lunch)
    assertEquals(recorded.entry.occurredAt, noon)
    assertEquals((recorded.entry.localDate, recorded.entry.zone), (today, kyiv))
    assertEquals(recorded.entry.text, None)
    assertEquals(recorded.day.entries, List(recorded.entry))
    assert(!recorded.replayed)

  test("the words are stored as given"):
    val rig = Rig()
    assertEquals(rig.log(MealType.Dinner, "k1", Some("Soup and bread")).entry.text.map(_.value), Some("Soup and bread"))

  test("the same tap delivered twice records one meal"):
    val rig = Rig()
    val first = rig.log(MealType.Lunch, "k1")
    val again = rig.log(MealType.Lunch, "k1")
    assertEquals(again.entry, first.entry)
    assert(again.replayed)
    assertEquals(rig.tables.meals.live.size, 1)

  test("Undo removes the meal within 15 minutes and not after"):
    val rig = Rig()
    val first = rig.log(MealType.Lunch, "k1")
    rig.clock.advance(Duration.ofMinutes(15))
    assertEquals(rig.service.undo(rig.tx, account, first.entry.id).map(_.changed), Right(true))
    assertEquals(rig.tables.meals.live, Nil)
    val second = rig.log(MealType.Snack, "k2")
    rig.clock.advance(Duration.ofMinutes(16))
    assertEquals(rig.service.undo(rig.tx, account, second.entry.id), Left(MealRefusal.UndoWindowPassed))

  test("a meal of another account is unknown"):
    val rig = Rig()
    val mine = rig.log(MealType.Lunch, "k1")
    assertEquals(rig.service.delete(rig.tx, stranger, mine.entry.id), Left(MealRefusal.UnknownMeal))
    assertEquals(rig.service.get(rig.tx, stranger, mine.entry.id), Left(MealRefusal.UnknownMeal))
    assertEquals(rig.tables.meals.live.size, 1)

  test("a change is a new entry that supersedes the old one and keeps its time when none is given"):
    val rig = Rig()
    val original = rig.log(MealType.Lunch, "k1", Some("soup"))
    rig.clock.advance(Duration.ofHours(2))
    val changed = rig.service
      .change(rig.tx, ChangeMeal(account, original.entry.id, MealInput(MealType.Dinner, words("pasta"), None), EntrySource.MiniApp, "k2"))
      .fold(refusal => fail(s"refused: $refusal"), identity)
    assertEquals(changed.entry.supersedesId, Some(original.entry.id))
    assertEquals(changed.entry.occurredAt, original.entry.occurredAt)
    assertEquals(changed.entry.mealType, MealType.Dinner)
    assertEquals(rig.tables.meals.live.map(_.id), List(changed.entry.id))
    val stale = rig.service.change(
      rig.tx,
      ChangeMeal(account, original.entry.id, MealInput(MealType.Snack, None, None), EntrySource.MiniApp, "k3")
    )
    assertEquals(stale, Left(MealRefusal.UnknownMeal))

  test("a meal can be added for a past day and lands on that local day (R-NUT-10, R-NUT-2)"):
    val rig = Rig()
    // 23:30 in Kyiv on 13 June.
    val recorded = rig.log(MealType.Dinner, "k1", at = Some(Instant.parse("2026-06-13T20:30:00Z")))
    assertEquals(recorded.entry.localDate, LocalDate.parse("2026-06-13"))

  test("a meal time in the future is refused"):
    val rig = Rig()
    val later = noon.plus(Duration.ofHours(1))
    assertEquals(rig.service.log(rig.tx, rig.command(MealType.Dinner, "k1", at = Some(later))), Left(MealRefusal.InFuture))

  test("delete removes a meal of any age; a second delete changes nothing"):
    val rig = Rig()
    val meal = rig.log(MealType.Breakfast, "k1")
    rig.clock.advance(Duration.ofDays(3))
    assertEquals(rig.service.delete(rig.tx, account, meal.entry.id).map(_.changed), Right(true))
    assertEquals(rig.service.delete(rig.tx, account, meal.entry.id).map(_.changed), Right(false))

  test("recent words are the distinct words of one meal type, newest first, at most five"):
    val rig = Rig()
    List("soup", "salad", "soup", "pasta", "rice", "wrap", "curry").zipWithIndex.foreach: (text, i) =>
      rig.clock.advance(Duration.ofMinutes(1))
      rig.log(MealType.Lunch, s"k$i", Some(text))
    rig.log(MealType.Dinner, "d", Some("steak"))
    assertEquals(
      rig.service.recent(rig.tx, account, MealType.Lunch).map(_.map(_.value)),
      Right(List("curry", "wrap", "rice", "pasta", "soup"))
    )

  test("the plan is breakfast, lunch and dinner until the user sets one, and may be empty"):
    val rig = Rig()
    assertEquals(rig.service.plan(rig.tx, account), Right(MealPlan.Default))
    assertEquals(rig.service.setPlan(rig.tx, account, MealPlan(Set.empty)), Right(MealPlan(Set.empty)))
    assertEquals(rig.service.plan(rig.tx, account), Right(MealPlan(Set.empty)))

  test("each write queues the nutrition rollup of its day"):
    val rig = Rig()
    rig.log(MealType.Lunch, "k1")
    assertEquals(rig.service.list(rig.tx, account, DateRange.single(today)).map(_.size), Right(1))
    assert(rig.tables.dirty.exists(key => key.localDate == today && key.domain.dbValue == "nutrition"))
