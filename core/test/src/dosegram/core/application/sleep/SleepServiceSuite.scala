package dosegram.core.application.sleep

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.ManualClock
import dosegram.core.application.SequentialIds
import dosegram.core.application.TrackingTxDouble
import dosegram.core.domain.EntrySource
import dosegram.core.domain.sleep.CheckinKind
import dosegram.core.domain.sleep.Confidence
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEpisode
import dosegram.core.domain.sleep.SleepInvalid
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepOrigin
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.ConsentPurpose

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** ROADMAP G7.1 acceptance, domain part, on the in-memory tables: consent first, the two taps, exactly-once by client
  * key, no episode from a guess, entry after the fact, correction as a new row, rating, Undo.
  */
class SleepServiceSuite extends munit.FunSuite:

  private val account = UUID.fromString("00000000-0000-7000-8000-0000000000c1")
  private val berlin = ZoneId.of("Europe/Berlin")
  // 23:40 local on 11 November 2026.
  private val bedtime = Instant.parse("2026-11-11T22:40:00Z")
  private val morning = Instant.parse("2026-11-12T06:10:00Z")

  private final class Rig(consent: FakeConsent = FakeConsent.all(account)):
    val clock = ManualClock(bedtime)
    val tables = InMemoryTracking(Map(account -> berlin))
    val tx = TrackingTxDouble(tables, consent)
    val service = SleepService(clock, SequentialIds())

    def input(key: String): SleepInput = SleepInput(account, EntrySource.Bot, key)

    def night(): SleepEpisode =
      service.goingToSleep(tx, input("night"))
      clock.set(morning)
      service.wakeUp(tx, input("morning")) match
        case Right(WokeUp.Closed(episode, _)) => episode
        case other                            => fail(s"expected a closed night, got $other")

    def entered(from: String, to: String, key: String, kind: SleepKind = SleepKind.Main): EnterSleep =
      EnterSleep(input(key), kind, Instant.parse(from), Instant.parse(to))

    def live: List[SleepEpisode] = tables.sleep.live

  test("nothing is stored or shown before the consent"):
    val rig = Rig(FakeConsent(Set(account -> ConsentPurpose.Mood)))
    assertEquals(rig.service.goingToSleep(rig.tx, rig.input("k1")), Left(SleepRefusal.ConsentMissing))
    assertEquals(rig.service.wakeUp(rig.tx, rig.input("k2")), Left(SleepRefusal.ConsentMissing))
    assertEquals(rig.service.status(rig.tx, account), Left(SleepRefusal.ConsentMissing))
    assert(rig.tables.sleep.checkins.isEmpty && rig.tables.sleep.episodes.isEmpty)

  test("going to sleep at 23:40 and up at 07:10 is one tier 2 night of 7 h 30 min on the wake date (AC-SLP-1)"):
    val rig = Rig()
    val night = rig.night()
    assertEquals(night.duration, Duration.ofMinutes(450))
    assertEquals(night.sleepDay, LocalDate.parse("2026-11-12"))
    assertEquals((night.kind, night.tier, night.origin), (SleepKind.Main, SleepTier.Explicit, SleepOrigin.CheckIn))
    assertEquals(night.confidence, Confidence.BothExplicit)
    assertEquals((night.interval.onset.source, night.interval.wake.source), (EdgeSource.CheckIn, EdgeSource.CheckIn))
    assertEquals(rig.live.size, 1)

  test("the same tap delivered twice records one check-in and one night"):
    val rig = Rig()
    val first = rig.service.goingToSleep(rig.tx, rig.input("night"))
    val again = rig.service.goingToSleep(rig.tx, rig.input("night"))
    assertEquals(again.map(_.checkin.id), first.map(_.checkin.id))
    assertEquals(again.map(_.replayed), Right(true))
    rig.clock.set(morning)
    val Right(WokeUp.Closed(night, false)) = rig.service.wakeUp(rig.tx, rig.input("morning")): @unchecked
    assertEquals(rig.service.wakeUp(rig.tx, rig.input("morning")), Right(WokeUp.Closed(night, replayed = true)))
    assertEquals(rig.live.size, 1)
    assertEquals(rig.tables.sleep.checkins.size, 2)

  test("a second Going to sleep replaces the open onset (R-SLP-4)"):
    val rig = Rig()
    rig.service.goingToSleep(rig.tx, rig.input("first"))
    rig.clock.advance(Duration.ofMinutes(50))
    val second = rig.service.goingToSleep(rig.tx, rig.input("second"))
    assertEquals(second.map(_.replacedOpen), Right(true))
    rig.clock.set(morning)
    val Right(WokeUp.Closed(night, _)) = rig.service.wakeUp(rig.tx, rig.input("morning")): @unchecked
    assertEquals(night.interval.onset.at, bedtime.plus(Duration.ofMinutes(50)))

  test("I'm up without an open night creates no episode: it records the tap and asks for the bedtime"):
    val rig = Rig()
    rig.clock.set(morning)
    val Right(WokeUp.NeedsBedtime(wake)) = rig.service.wakeUp(rig.tx, rig.input("morning")): @unchecked
    assertEquals(wake.kind, CheckinKind.ImUp)
    assertEquals(rig.live, Nil)

  test("the bedtime answer completes that night with one entered and one tapped edge"):
    val rig = Rig()
    rig.clock.set(morning)
    val Right(WokeUp.NeedsBedtime(wake)) = rig.service.wakeUp(rig.tx, rig.input("morning")): @unchecked
    val completed =
      rig.service.completeNight(rig.tx, CompleteNight(rig.input("chip"), wake.id, LocalTime.parse("23:30")))
    val night = completed.fold(refusal => fail(s"refused: $refusal"), _.episode)
    assertEquals(night.duration, Duration.ofMinutes(460))
    assertEquals(night.origin, SleepOrigin.Retrospective)
    assertEquals(
      (night.interval.onset.source, night.interval.wake.source),
      (EdgeSource.Retrospective, EdgeSource.CheckIn)
    )

  test("a Going to sleep older than 18 hours is no longer open and is not counted (R-SLP-4)"):
    val rig = Rig()
    rig.service.goingToSleep(rig.tx, rig.input("night"))
    rig.clock.advance(Duration.ofHours(18).plusMinutes(1))
    val woke = rig.service.wakeUp(rig.tx, rig.input("late"))
    assert(woke.exists(_.isInstanceOf[WokeUp.NeedsBedtime]), woke.toString)
    assertEquals(rig.live, Nil)

  test("I'm up within an hour of Going to sleep is not a night; the open tap stays"):
    val rig = Rig()
    rig.service.goingToSleep(rig.tx, rig.input("night"))
    rig.clock.advance(Duration.ofMinutes(20))
    val woke = rig.service.wakeUp(rig.tx, rig.input("early"))
    assert(woke.left.exists(_.isInstanceOf[SleepRefusal.Invalid]), woke.toString)
    assertEquals(rig.service.status(rig.tx, account).map(_.open.isDefined), Right(true))
    assertEquals(rig.tables.sleep.checkins.size, 1)

  test("a night entered after the fact has two entered edges (R-SLP-6)"):
    val rig = Rig()
    rig.clock.set(morning.plus(Duration.ofHours(3)))
    val recorded = rig.service.enter(rig.tx, rig.entered("2026-11-11T22:40:00Z", "2026-11-12T06:10:00Z", "typed"))
    val night = recorded.fold(refusal => fail(s"refused: $refusal"), _.episode)
    assertEquals(night.origin, SleepOrigin.Retrospective)
    assertEquals(night.interval.onset.source, EdgeSource.Retrospective)
    assertEquals(night.duration, Duration.ofMinutes(450))

  test("an entry that ends in the future or breaks the bounds is refused (R-SLP-2)"):
    val rig = Rig()
    rig.clock.set(morning)
    assertEquals(
      rig.service.enter(rig.tx, rig.entered("2026-11-11T22:40:00Z", "2026-11-12T07:10:00Z", "future")),
      Left(SleepRefusal.Invalid(SleepInvalid.InFuture))
    )
    val tooLong = rig.service.enter(rig.tx, rig.entered("2026-11-11T10:00:00Z", "2026-11-12T06:00:00Z", "long"))
    assert(tooLong.left.exists(_.isInstanceOf[SleepRefusal.Invalid]), tooLong.toString)
    assertEquals(rig.live, Nil)

  test("a second night for a sleep day is refused with the stored one, and replacing keeps the old row"):
    val rig = Rig()
    val first = rig.night()
    rig.clock.advance(Duration.ofHours(2))
    val again = rig.entered("2026-11-11T22:00:00Z", "2026-11-12T06:00:00Z", "typed")
    assertEquals(rig.service.enter(rig.tx, again), Left(SleepRefusal.NightExists(first)))
    val replaced = rig.service.replace(rig.tx, again, first.id).fold(r => fail(s"refused: $r"), _.episode)
    assertEquals(replaced.supersedesId, Some(first.id))
    assertEquals(rig.live.map(_.id), List(replaced.id))
    assertEquals(rig.tables.sleep.episodes(first.id).isLive, false)

  test("episodes of one account do not overlap (R-SLP-2)"):
    val rig = Rig()
    val night = rig.night()
    rig.clock.advance(Duration.ofHours(2))
    val nap = rig.entered("2026-11-12T05:30:00Z", "2026-11-12T06:30:00Z", "nap", SleepKind.Nap)
    assertEquals(rig.service.enter(rig.tx, nap), Left(SleepRefusal.Overlaps(night)))

  test("a nap is its own episode beside the night of the same sleep day (R-SLP-7)"):
    val rig = Rig()
    rig.night()
    rig.clock.set(Instant.parse("2026-11-12T13:00:00Z"))
    val nap = rig.entered("2026-11-12T12:20:00Z", "2026-11-12T12:40:00Z", "nap", SleepKind.Nap)
    assert(rig.service.enter(rig.tx, nap).isRight)
    assertEquals(rig.live.map(_.kind), List(SleepKind.Main, SleepKind.Nap))

  test("adjusting an edge stores a new row, marks the moved edge as entered and keeps the other (R-SLP-3)"):
    val rig = Rig()
    val original = rig.night()
    val command = AdjustEpisode(rig.input("adjust"), original.id, Duration.ofMinutes(-15), Duration.ZERO)
    val adjusted = rig.service.adjust(rig.tx, command).fold(r => fail(s"refused: $r"), _.episode)
    assertEquals(adjusted.duration, Duration.ofMinutes(465))
    assertEquals(
      (adjusted.interval.onset.source, adjusted.interval.wake.source),
      (EdgeSource.Retrospective, EdgeSource.CheckIn)
    )
    assertEquals(adjusted.supersedesId, Some(original.id))
    assertEquals(rig.live.map(_.id), List(adjusted.id))

  test("an adjustment that moves nothing stores nothing"):
    val rig = Rig()
    val original = rig.night()
    val command = AdjustEpisode(rig.input("adjust"), original.id, Duration.ZERO, Duration.ZERO)
    assertEquals(rig.service.adjust(rig.tx, command).map(_.episode.id), Right(original.id))
    assertEquals(rig.tables.sleep.episodes.size, 1)

  test("the rating is carried by a new row and survives a later adjustment"):
    val rig = Rig()
    val original = rig.night()
    val rated = rig.service
      .rate(rig.tx, RateEpisode(rig.input("rate"), original.id, Quality.of(4).get))
      .fold(r => fail(s"refused: $r"), _.episode)
    assertEquals(rated.quality.map(_.step), Some(4))
    assertEquals(rated.interval, original.interval)
    val command = AdjustEpisode(rig.input("adjust"), rated.id, Duration.ZERO, Duration.ofMinutes(15))
    val Left(SleepRefusal.Invalid(SleepInvalid.InFuture)) = rig.service.adjust(rig.tx, command): @unchecked
    rig.clock.advance(Duration.ofMinutes(20))
    assertEquals(rig.service.adjust(rig.tx, command).map(_.episode.quality.map(_.step)), Right(Some(4)))

  test("Undo removes the open tap, and removes a night only inside the undo window"):
    val rig = Rig()
    val Right(started) = rig.service.goingToSleep(rig.tx, rig.input("mistaken")): @unchecked
    assertEquals(rig.service.undoCheckin(rig.tx, account, started.checkin.id), Right(true))
    assertEquals(rig.service.status(rig.tx, account).map(_.open), Right(None))
    val night = rig.night()
    rig.clock.advance(Duration.ofMinutes(16))
    assertEquals(rig.service.undoEpisode(rig.tx, account, night.id), Left(SleepRefusal.UndoWindowPassed))
    rig.clock.advance(Duration.ofMinutes(-10))
    assertEquals(rig.service.undoEpisode(rig.tx, account, night.id), Right(true))
    assertEquals(rig.live, Nil)

  test("a delete removes a night of any age; a second delete changes nothing, an unknown night is refused"):
    val rig = Rig()
    val night = rig.night()
    rig.clock.advance(Duration.ofDays(3))
    assertEquals(rig.service.delete(rig.tx, account, night.id), Right(true))
    assertEquals(rig.live, Nil)
    assertEquals(rig.service.delete(rig.tx, account, night.id), Right(false))
    assertEquals(rig.service.delete(rig.tx, account, UUID.randomUUID()), Left(SleepRefusal.UnknownEpisode))

  test("the goal is the user's own number inside the input bounds; it can be removed"):
    val rig = Rig()
    assertEquals(rig.service.setGoal(rig.tx, account, Some(450)), Right(Some(450)))
    assertEquals(rig.service.status(rig.tx, account).map(_.goalMinutes), Right(Some(450)))
    assertEquals(rig.service.setGoal(rig.tx, account, Some(30)), Left(SleepRefusal.GoalOutOfBounds))
    assertEquals(rig.service.setGoal(rig.tx, account, None), Right(None))
    assertEquals(rig.service.status(rig.tx, account).map(_.goalMinutes), Right(None))
end SleepServiceSuite
