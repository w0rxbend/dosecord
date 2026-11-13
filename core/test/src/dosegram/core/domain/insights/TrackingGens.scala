package dosegram.core.domain.insights

import dosegram.core.domain.EntrySource
import dosegram.core.domain.hydration.EnteredAmount
import dosegram.core.domain.hydration.GoalChange
import dosegram.core.domain.hydration.HydrationEntry
import dosegram.core.domain.hydration.HydrationUnit
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import org.scalacheck.Gen

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Builders and generators for hydration entries and mood check-ins over a fixed 30-day window. */
object TrackingGens:

  val account: UUID = UUID.fromString("00000000-0000-7000-8000-0000000000aa")
  val windowEnd: LocalDate = LocalDate.parse("2026-06-30")
  val month: DateRange = DateRange.endingOn(windowEnd, 30)
  val week: DateRange = DateRange.endingOn(windowEnd, 7)

  def water(at: Instant, zone: ZoneId, millilitres: Int): HydrationEntry =
    HydrationEntry(
      id = UUID.randomUUID(),
      accountId = account,
      occurredAt = at,
      localDate = LocalDays.dateOf(at, zone),
      zone = zone,
      amount = WaterAmount(millilitres, EnteredAmount(BigDecimal(millilitres), HydrationUnit.Millilitre)),
      source = EntrySource.Bot,
      clientKey = None,
      createdAt = at,
      voidedAt = None
    )

  def waterOn(date: LocalDate, millilitres: Int): HydrationEntry =
    water(LocalDays.startOf(date, InsightGens.utc).plusSeconds(43200), InsightGens.utc, millilitres)

  def mood(at: Instant, zone: ZoneId, valence: Int, energy: Option[Int]): MoodCheckin =
    MoodCheckin(
      id = UUID.randomUUID(),
      accountId = account,
      occurredAt = at,
      localDate = LocalDays.dateOf(at, zone),
      zone = zone,
      valence = Valence.of(valence).get,
      energy = energy.map(Energy.of(_).get),
      tagIds = Nil,
      source = EntrySource.Bot,
      clientKey = None,
      supersedesId = None,
      createdAt = at,
      voidedAt = None
    )

  def moodOn(date: LocalDate, valence: Int, energy: Option[Int] = None): MoodCheckin =
    mood(LocalDays.startOf(date, InsightGens.utc).plusSeconds(43200), InsightGens.utc, valence, energy)

  val genDate: Gen[LocalDate] = Gen.oneOf(month.dates)

  val genWater: Gen[HydrationEntry] =
    for
      date <- genDate
      millilitres <- Gen.choose(1, 5000)
    yield waterOn(date, millilitres)

  val genWaters: Gen[List[HydrationEntry]] = Gen.choose(0, 60).flatMap(Gen.listOfN(_, genWater))

  val genGoals: Gen[List[GoalChange]] =
    Gen
      .choose(0, 3)
      .flatMap(Gen.listOfN(_, Gen.zip(genDate, Gen.option(Gen.choose(100, 10000)))))
      .map(_.distinctBy(_._1).map(GoalChange(_, _)))

  val genMood: Gen[MoodCheckin] =
    for
      date <- genDate
      valence <- Gen.choose(1, 5)
      energy <- Gen.option(Gen.choose(1, 5))
    yield moodOn(date, valence, energy)

  val genMoods: Gen[List[MoodCheckin]] = Gen.choose(0, 80).flatMap(Gen.listOfN(_, genMood))
end TrackingGens
