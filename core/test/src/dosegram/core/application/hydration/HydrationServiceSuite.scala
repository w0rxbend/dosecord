package dosegram.core.application.hydration

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.ManualClock
import dosegram.core.application.SequentialIds
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.HydrationDay
import dosegram.core.domain.insights.HydrationStats
import dosegram.core.ports.ConsentPurpose
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G5.3, G5.3b, G5.3c acceptance, on the in-memory tables: consent first, exactly-once by client key, Undo,
  * the user's own goal, "add for yesterday", and a day view that always equals the stored rows.
  */
class HydrationServiceSuite extends munit.ScalaCheckSuite:

  private val account = UUID.fromString("00000000-0000-7000-8000-0000000000a1")
  private val stranger = UUID.fromString("00000000-0000-7000-8000-0000000000a2")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val noon = Instant.parse("2026-06-15T09:00:00Z")
  private val today = LocalDate.parse("2026-06-15")

  private final class Rig(consent: FakeConsent = FakeConsent.all(account, stranger)):
    val clock = ManualClock(noon)
    val tables = InMemoryTracking(Map(account -> kyiv, stranger -> kyiv))
    val service = HydrationService(tables, consent, clock, SequentialIds())

    def log(millilitres: Int, key: String, day: EntryDay = EntryDay.Today, owner: UUID = account): WaterLogged =
      service.log(command(millilitres, key, day, owner)).fold(refusal => fail(s"refused: $refusal"), identity)

    def command(millilitres: Int, key: String, day: EntryDay = EntryDay.Today, owner: UUID = account): LogWater =
      LogWater(owner, WaterAmount.ofMillilitres(millilitres).toOption.get, day, EntrySource.Bot, key)

  test("nothing is stored before the consent, and every other request is refused the same way"):
    val rig = Rig(FakeConsent(Set(account -> ConsentPurpose.Mood)))
    assertEquals(rig.service.log(rig.command(250, "k1")), Left(HydrationRefusal.ConsentMissing))
    assertEquals(rig.service.day(account, EntryDay.Today), Left(HydrationRefusal.ConsentMissing))
    assertEquals(rig.service.setGoal(account, Some(2000)), Left(HydrationRefusal.ConsentMissing))
    assertEquals(rig.service.presets(account), Left(HydrationRefusal.ConsentMissing))
    assert(rig.tables.entries.isEmpty)

  test("one tap records one entry with the local date and zone of the account"):
    val rig = Rig()
    val logged = rig.log(250, "k1")
    assertEquals(logged.entry.localDate, today)
    assertEquals(logged.entry.zone, kyiv)
    assertEquals(logged.entry.occurredAt, noon)
    assertEquals(logged.day.recorded, Some(HydrationDay(250, 1, None)))
    assert(!logged.replayed)

  test("the same control delivered twice creates one entry; two presses of a fresh control create two (AC-HYD-2)"):
    val rig = Rig()
    val first = rig.log(250, "k1")
    val retried = rig.log(250, "k1")
    assertEquals(retried.entry.id, first.entry.id)
    assert(retried.replayed)
    assertEquals(rig.tables.entries.size, 1)
    val second = rig.log(250, "k2")
    assertEquals(second.day.recorded.map(_.totalMl), Some(500))
    assertEquals(rig.tables.entries.size, 2)

  test("a client key is unique per account, not across accounts"):
    val rig = Rig()
    rig.log(250, "k1")
    val other = rig.log(300, "k1", owner = stranger)
    assert(!other.replayed)
    assertEquals(other.day.recorded.map(_.totalMl), Some(300))

  test("the entry that first takes the day to the goal says so once"):
    val rig = Rig()
    rig.service.setGoal(account, Some(500))
    assert(!rig.log(250, "k1").goalReachedNow)
    assert(rig.log(250, "k2").goalReachedNow)
    assert(!rig.log(250, "k3").goalReachedNow)
    assert(!rig.log(250, "k2").goalReachedNow)

  test("an entry added for yesterday carries yesterday's local date and changes yesterday's total only"):
    val rig = Rig()
    rig.log(300, "k1")
    val added = rig.log(500, "k2", EntryDay.Yesterday)
    assertEquals(added.entry.localDate, today.minusDays(1))
    assertEquals(added.entry.occurredAt, Instant.parse("2026-06-14T20:59:59Z"))
    assertEquals(added.day.recorded.map(_.totalMl), Some(500))
    assertEquals(rig.service.day(account, EntryDay.Today).map(_.recorded.map(_.totalMl)), Right(Some(300)))

  test("an entry just before local midnight stays on its day after the account moves to another zone (AC-HYD-5)"):
    val rig = Rig()
    rig.clock.set(Instant.parse("2026-06-15T20:50:00Z"))
    val late = rig.log(200, "k1")
    assertEquals(late.entry.localDate, today)
    rig.tables.setZone(account, ZoneId.of("Asia/Tokyo"))
    rig.clock.set(Instant.parse("2026-06-15T21:10:00Z"))
    val view = rig.service.day(account, EntryDay.Yesterday).toOption.get
    assertEquals((view.date, view.recorded.map(_.totalMl)), (today, Some(200)))

  test("Undo voids the entry, the total follows at once, and a second Undo changes nothing"):
    val rig = Rig()
    rig.log(300, "k1")
    val second = rig.log(500, "k2")
    rig.clock.advance(Duration.ofMinutes(14))
    val undone = rig.service.undo(account, second.entry.id).toOption.get
    assertEquals((undone.changed, undone.day.recorded.map(_.totalMl)), (true, Some(300)))
    val again = rig.service.undo(account, second.entry.id).toOption.get
    assertEquals((again.changed, again.day.recorded.map(_.totalMl)), (false, Some(300)))

  test("Undo is refused after 15 minutes and leaves the entry (AC-HYD-9)"):
    val rig = Rig()
    val logged = rig.log(300, "k1")
    rig.clock.advance(Duration.ofMinutes(15).plusSeconds(1))
    assertEquals(rig.service.undo(account, logged.entry.id), Left(HydrationRefusal.UndoWindowPassed))
    assertEquals(rig.service.day(account, EntryDay.Today).map(_.recorded.map(_.totalMl)), Right(Some(300)))

  test("an entry can be deleted at any age, and a day whose only entry is gone has no record"):
    val rig = Rig()
    val logged = rig.log(300, "k1")
    rig.clock.advance(Duration.ofHours(5))
    val removed = rig.service.delete(account, logged.entry.id).toOption.get
    assertEquals((removed.changed, removed.day.recorded), (true, None))

  test("an entry of another account is unknown and stays untouched"):
    val rig = Rig()
    val logged = rig.log(300, "k1")
    assertEquals(rig.service.undo(stranger, logged.entry.id), Left(HydrationRefusal.UnknownEntry))
    assertEquals(rig.service.delete(stranger, logged.entry.id), Left(HydrationRefusal.UnknownEntry))
    assert(rig.tables.entries(logged.entry.id).isLive)

  test("a retried tap after Undo replays the voided entry and records nothing new"):
    val rig = Rig()
    val logged = rig.log(300, "k1")
    rig.service.undo(account, logged.entry.id)
    val retried = rig.log(300, "k1")
    assert(retried.replayed)
    assertEquals(retried.day.recorded, None)

  test("the goal is the user's own, within 100 to 10000 ml, and may be removed"):
    val rig = Rig()
    rig.log(1000, "k1")
    assertEquals(rig.service.setGoal(account, Some(99)), Left(HydrationRefusal.GoalOutOfBounds(100, 10000)))
    assertEquals(rig.service.setGoal(account, Some(10001)), Left(HydrationRefusal.GoalOutOfBounds(100, 10000)))
    assertEquals(rig.service.setGoal(account, Some(2000)).map(_.recorded), Right(Some(HydrationDay(1000, 1, Some(2000)))))
    assertEquals(rig.service.setGoal(account, None).map(_.recorded), Right(Some(HydrationDay(1000, 1, None))))

  test("the 61st entry of a local day is refused with the limit"):
    val rig = Rig()
    (1 to 60).foreach(index => rig.log(10, s"k$index"))
    assertEquals(rig.service.log(rig.command(10, "k61")), Left(HydrationRefusal.DailyLimitReached(60)))
    assertEquals(rig.log(10, "y1", EntryDay.Yesterday).day.recorded.map(_.entries), Some(1))

  test("the presets default to 200, 300 and 500 ml until the user sets up to four of their own"):
    val rig = Rig()
    assertEquals(rig.service.presets(account), Right(List(200, 300, 500)))
    assertEquals(rig.service.setPresets(account, List(250, 500)), Right(List(250, 500)))
    assertEquals(rig.service.presets(account), Right(List(250, 500)))
    assertEquals(rig.service.setPresets(account, List(1, 2, 3, 4, 5)), Left(HydrationRefusal.PresetsInvalid))
    assertEquals(rig.service.setPresets(account, List(250, 6000)), Left(HydrationRefusal.PresetsInvalid))
    assertEquals(rig.service.setPresets(account, Nil), Left(HydrationRefusal.PresetsInvalid))

  test("an unknown account is refused"):
    val unknown = UUID.fromString("00000000-0000-7000-8000-0000000000a9")
    val rig = Rig(FakeConsent.all(unknown))
    assertEquals(rig.service.log(rig.command(250, "k1", owner = unknown)), Left(HydrationRefusal.UnknownAccount))

  private enum Step:
    case Log(millilitres: Int, day: EntryDay)
    case Undo(pick: Int)
    case Delete(pick: Int)
    case Goal(target: Option[Int])
    case Wait(minutes: Int)

  private val genStep: Gen[Step] = Gen.frequency(
    5 -> Gen.zip(Gen.choose(1, 5000), Gen.oneOf(EntryDay.Today, EntryDay.Yesterday)).map(Step.Log(_, _)),
    2 -> Gen.choose(0, 50).map(Step.Undo(_)),
    1 -> Gen.choose(0, 50).map(Step.Delete(_)),
    1 -> Gen.option(Gen.choose(100, 10000)).map(Step.Goal(_)),
    1 -> Gen.choose(1, 20).map(Step.Wait(_))
  )

  property("after any sequence of entries, voids and Undo the day view equals the stored rows"):
    forAll(Gen.listOf(genStep)) { steps =>
      val rig = Rig()
      steps.zipWithIndex.foreach { (step, index) =>
        val ids = rig.tables.entries.keys.toVector
        step match
          case Step.Log(millilitres, day) => rig.service.log(rig.command(millilitres, s"k$index", day))
          case Step.Undo(pick) if ids.nonEmpty   => rig.service.undo(account, ids(pick % ids.size))
          case Step.Delete(pick) if ids.nonEmpty => rig.service.delete(account, ids(pick % ids.size))
          case Step.Goal(target)                 => rig.service.setGoal(account, target)
          case Step.Wait(minutes)                => rig.clock.advance(Duration.ofMinutes(minutes.toLong))
          case Step.Undo(_) | Step.Delete(_)     => ()
      }
      val range = DateRange.endingOn(today, 2)
      val expected = rig.tables.transaction(tx =>
        HydrationStats.byDay(tx.hydration.listLive(account, range), tx.hydration.goals(account, today))
      )
      List(EntryDay.Today, EntryDay.Yesterday).foreach { day =>
        val view = rig.service.day(account, day).toOption.get
        assertEquals(view.recorded, expected.get(view.date), clue(day))
      }
    }
end HydrationServiceSuite
