package dosegram.infra.db

import dosegram.contracts.HhMm
import dosegram.contracts.Weekday
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepInput
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.hydration.WaterAmount
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.random.RandomGenerator
import javax.sql.DataSource

/** An account with a record in every domain that exists (ROADMAP G3.10: "the export of a fixture account"): a
  * medication with a schedule and a taken, a skipped and a missed dose, water, mood and sleep entries over several days,
  * a delivery channel and its consents. `now` is the end of the recorded days.
  */
final class ExportFixture(dataSource: DataSource, val now: Instant, val zone: ZoneId):
  private val fixtures = Fixtures(dataSource)
  private val clock = MutableClock(now.minus(Duration.ofDays(ExportFixture.Days.toLong)))
  private val tracking =
    TransactionalTracking(PgUnitOfWork(dataSource, clock), clock, IdSource.uuidV7(RandomGenerator.getDefault))

  val account: UUID = fixtures.account(zone.getId)
  val medicationName = "Canary-Cetirizine"

  def seed(): ExportFixture =
    fixtures.grant(
      account,
      ConsentPurpose.Medication,
      ConsentPurpose.Hydration,
      ConsentPurpose.Mood,
      ConsentPurpose.Sleep
    )
    fixtures.deliveryChannel(account, "fake", s"chat-$account", clock.now())
    val rule = Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, List(HhMm.unsafe("09:00")))))
    val schedule =
      fixtures.schedule(clock, account, medicationName, rule, zone, doseAmount = Some(BigDecimal(10)), doseUnit = Some("mg"))
    val scheduleId = schedule.scheduleId
    val first = clock.now()
    (1 to ExportFixture.Days).foreach: day =>
      val at = first.plus(Duration.ofDays(day.toLong - 1)).plus(Duration.ofHours(10))
      clock.at = at
      val status = day % 3 match
        case 0 => OccurrenceStatus.Skipped
        case 1 => OccurrenceStatus.Taken
        case _ => OccurrenceStatus.Missed
      fixtures.occurrenceAt(scheduleId, at.minus(Duration.ofMinutes(30)), s"fixture-$day", status)
      tracking.water.log(LogWater(account, WaterAmount.ofMillilitres(250 * day).toOption.get, EntryDay.Today,
        EntrySource.Bot, s"water-$day"))
      tracking.moods.checkIn(CheckIn(account, Valence.of(1 + day % 5).get, Energy.of(3), Nil, EntryDay.Today,
        EntrySource.Bot, s"mood-$day"))
      val wake = at.minus(Duration.ofHours(3))
      tracking.uow.transaction(tracking.sleepService.enter(_, EnterSleep(SleepInput(account, EntrySource.Bot,
        s"sleep-$day"), SleepKind.Main, wake.minus(Duration.ofMinutes(400L + day)), wake)))
    this
end ExportFixture

object ExportFixture:
  val Days = 6
