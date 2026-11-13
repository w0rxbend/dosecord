package dosegram.core.application.mood

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.ManualClock
import dosegram.core.application.SequentialIds
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G6.1 acceptance, domain part, on the in-memory tables: consent first, one tap, exactly-once by client key,
  * the optional energy step, Undo, and an outcome that has the same shape for every value.
  */
class MoodServiceSuite extends munit.FunSuite:

  private val account = UUID.fromString("00000000-0000-7000-8000-0000000000b1")
  private val stranger = UUID.fromString("00000000-0000-7000-8000-0000000000b2")
  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val evening = Instant.parse("2026-06-15T18:04:00Z")
  private val today = LocalDate.parse("2026-06-15")

  private final class Rig(consent: FakeConsent = FakeConsent.all(account, stranger)):
    val clock = ManualClock(evening)
    val tables = InMemoryTracking(Map(account -> kyiv, stranger -> kyiv))
    val service = MoodService(tables, consent, clock, SequentialIds())

    def command(valence: Int, key: String, energy: Option[Int] = None, day: EntryDay = EntryDay.Today): CheckIn =
      CheckIn(account, Valence.of(valence).get, energy.flatMap(Energy.of), Nil, day, EntrySource.Bot, key)

    def checkIn(valence: Int, key: String, energy: Option[Int] = None, day: EntryDay = EntryDay.Today): MoodRecorded =
      service.checkIn(command(valence, key, energy, day)).fold(refusal => fail(s"refused: $refusal"), identity)

  test("no check-in is stored before the consent"):
    val rig = Rig(FakeConsent(Set(account -> ConsentPurpose.Hydration)))
    assertEquals(rig.service.checkIn(rig.command(4, "k1")), Left(MoodRefusal.ConsentMissing))
    assertEquals(rig.service.day(account, EntryDay.Today), Left(MoodRefusal.ConsentMissing))
    assert(rig.tables.checkins.isEmpty)

  test("one tap records a check-in with valence only; energy stays absent"):
    val rig = Rig()
    val recorded = rig.checkIn(4, "k1")
    assertEquals(recorded.checkin.valence.step, 4)
    assertEquals(recorded.checkin.energy, None)
    assertEquals((recorded.checkin.localDate, recorded.checkin.zone), (today, kyiv))
    assertEquals(recorded.day.recorded.map(day => (day.n, day.mean, day.energyMean)), Some((1, 4.0, None)))

  test("the same tap delivered twice records one check-in"):
    val rig = Rig()
    val first = rig.checkIn(4, "k1")
    val retried = rig.checkIn(4, "k1")
    assertEquals((retried.checkin.id, retried.replayed), (first.checkin.id, true))
    assertEquals(rig.tables.checkins.size, 1)

  test("the outcome has the same shape for all 25 pairs of valence and energy (R-MOO-6)"):
    for
      valence <- 1 to 5
      energy <- 1 to 5
    do
      val rig = Rig()
      val recorded = rig.checkIn(valence, "k1", Some(energy))
      assertEquals(recorded.checkin.valence.step, valence)
      assertEquals(recorded.checkin.energy.map(_.step), Some(energy))
      assertEquals((recorded.replayed, recorded.day.recorded.map(_.n), recorded.day.latest), (false, Some(1), Some(recorded.checkin)))

  test("several check-ins a day are allowed; the day value is their mean and the latest is kept apart"):
    val rig = Rig()
    rig.checkIn(2, "k1")
    rig.clock.advance(Duration.ofMinutes(30))
    val second = rig.checkIn(5, "k2")
    assertEquals(second.day.recorded.map(_.mean), Some(3.5))
    assertEquals(second.day.latest.map(_.valence.step), Some(5))

  test("the energy step replaces the check-in by a copy that carries the energy"):
    val rig = Rig()
    val original = rig.checkIn(4, "k1").checkin
    rig.clock.advance(Duration.ofMinutes(1))
    val amended = rig.service.setEnergy(account, original.id, Energy.of(2).get, "k1-energy").toOption.get
    assertEquals(amended.checkin.energy.map(_.step), Some(2))
    assertEquals(amended.checkin.supersedesId, Some(original.id))
    assertEquals((amended.checkin.occurredAt, amended.checkin.localDate), (original.occurredAt, original.localDate))
    assert(!rig.tables.checkins(original.id).isLive)
    assertEquals(amended.day.recorded.map(day => (day.n, day.energyMean)), Some((1, Some(2.0))))

  test("a retried energy step replays, and a second energy step on the replaced check-in is refused"):
    val rig = Rig()
    val original = rig.checkIn(4, "k1").checkin
    val amended = rig.service.setEnergy(account, original.id, Energy.of(2).get, "e1").toOption.get
    val retried = rig.service.setEnergy(account, original.id, Energy.of(2).get, "e1").toOption.get
    assertEquals((retried.checkin.id, retried.replayed), (amended.checkin.id, true))
    assertEquals(rig.service.setEnergy(account, original.id, Energy.of(5).get, "e2"), Left(MoodRefusal.UnknownCheckin))
    assertEquals(rig.tables.checkins.values.count(_.isLive), 1)

  test("Undo voids the check-in within 15 minutes; a second Undo changes nothing; later it is refused (R-MOO-10)"):
    val rig = Rig()
    val recorded = rig.checkIn(1, "k1")
    rig.clock.advance(Duration.ofMinutes(15))
    val undone = rig.service.undo(account, recorded.checkin.id).toOption.get
    assertEquals((undone.changed, undone.day.recorded), (true, None))
    assertEquals(rig.service.undo(account, recorded.checkin.id).map(_.changed), Right(false))
    val later = rig.checkIn(3, "k2")
    rig.clock.advance(Duration.ofMinutes(16))
    assertEquals(rig.service.undo(account, later.checkin.id), Left(MoodRefusal.UndoWindowPassed))

  test("a check-in of another account is unknown and stays untouched"):
    val rig = Rig()
    val recorded = rig.checkIn(3, "k1")
    assertEquals(rig.service.undo(stranger, recorded.checkin.id), Left(MoodRefusal.UnknownCheckin))
    assertEquals(
      rig.service.setEnergy(stranger, recorded.checkin.id, Energy.of(3).get, "e1"),
      Left(MoodRefusal.UnknownCheckin)
    )
    assert(rig.tables.checkins(recorded.checkin.id).isLive)

  test("at most five distinct tags are stored with a check-in"):
    val rig = Rig()
    val tags = List.tabulate(6)(index => UUID(7L, index.toLong))
    assertEquals(rig.service.checkIn(rig.command(3, "k1").copy(tagIds = tags)), Left(MoodRefusal.TooManyTags(5)))
    val five = tags.take(5)
    val recorded = rig.service.checkIn(rig.command(3, "k2").copy(tagIds = five ++ five)).toOption.get
    assertEquals(recorded.checkin.tagIds, five)

  test("a check-in marked for yesterday counts on yesterday's local date"):
    val rig = Rig()
    val recorded = rig.checkIn(4, "k1", day = EntryDay.Yesterday)
    assertEquals(recorded.checkin.localDate, today.minusDays(1))
    assertEquals(rig.service.day(account, EntryDay.Today).map(_.recorded), Right(None))
end MoodServiceSuite
