package dosegram.infra.db

import dosegram.core.application.hydration.HydrationRefusal
import dosegram.core.application.hydration.HydrationService
import dosegram.core.application.hydration.LogWater
import dosegram.core.application.hydration.WaterDayView
import dosegram.core.application.hydration.WaterLogged
import dosegram.core.application.hydration.WaterRemoved
import dosegram.core.application.insights.ComparisonWindow
import dosegram.core.application.insights.HydrationPeriodView
import dosegram.core.application.insights.MedicationPeriodView
import dosegram.core.application.insights.MoodPeriodView
import dosegram.core.application.insights.PeriodSummary
import dosegram.core.application.insights.RollupService
import dosegram.core.application.insights.StatsQueryService
import dosegram.core.application.insights.StatsRefusal
import dosegram.core.application.mood.CheckIn
import dosegram.core.application.mood.MoodDayView
import dosegram.core.application.mood.MoodRecorded
import dosegram.core.application.mood.MoodRefusal
import dosegram.core.application.mood.MoodRemoved
import dosegram.core.application.mood.MoodService
import dosegram.core.application.mood.SetEnergy
import dosegram.core.application.sleep.EnterSleep
import dosegram.core.application.sleep.SleepRecorded
import dosegram.core.application.sleep.SleepRefusal
import dosegram.core.application.sleep.SleepService
import dosegram.core.domain.EntryDay
import dosegram.core.domain.insights.Comparison
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.Energy
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentLedger
import dosegram.core.ports.ConsentNotice
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.ConsentTaps
import dosegram.core.ports.GrantedConsent
import dosegram.core.ports.IdSource
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.Instant
import java.util.UUID

/** The tracking use cases with one transaction per call: the shape of a caller outside a chat event. The Postgres
  * suites seed and read through it, so a test line stays one call.
  */
final class TransactionalTracking(val uow: UnitOfWork, clock: Clock, ids: IdSource):
  val rollups: RollupService = RollupService(uow, clock)
  val hydrationService: HydrationService = HydrationService(clock, ids)
  val moodService: MoodService = MoodService(clock, ids)
  val statsService: StatsQueryService = StatsQueryService(rollups, clock)
  val sleepService: SleepService = SleepService(clock, ids)

  object water:
    def log(command: LogWater): Either[HydrationRefusal, WaterLogged] =
      uow.transaction(hydrationService.log(_, command))
    def undo(accountId: UUID, entryId: UUID): Either[HydrationRefusal, WaterRemoved] =
      uow.transaction(hydrationService.undo(_, accountId, entryId))
    def delete(accountId: UUID, entryId: UUID): Either[HydrationRefusal, WaterRemoved] =
      uow.transaction(hydrationService.delete(_, accountId, entryId))
    def setGoal(accountId: UUID, targetMl: Option[Int]): Either[HydrationRefusal, WaterDayView] =
      uow.transaction(hydrationService.setGoal(_, accountId, targetMl))
    def day(accountId: UUID, day: EntryDay): Either[HydrationRefusal, WaterDayView] =
      uow.transaction(hydrationService.day(_, accountId, day))

  object moods:
    def checkIn(command: CheckIn): Either[MoodRefusal, MoodRecorded] =
      uow.transaction(moodService.checkIn(_, command))
    def setEnergy(accountId: UUID, checkinId: UUID, energy: Energy, key: String): Either[MoodRefusal, MoodRecorded] =
      uow.transaction(moodService.setEnergy(_, SetEnergy(accountId, checkinId, energy, key)))
    def day(accountId: UUID, day: EntryDay): Either[MoodRefusal, MoodDayView] =
      uow.transaction(moodService.day(_, accountId, day))
    def undo(accountId: UUID, checkinId: UUID): Either[MoodRefusal, MoodRemoved] =
      uow.transaction(moodService.undo(_, accountId, checkinId))

  object sleeps:
    def enter(command: EnterSleep): Either[SleepRefusal, SleepRecorded] =
      uow.transaction(sleepService.enter(_, command))
    def setGoal(accountId: UUID, targetMinutes: Option[Int]): Either[SleepRefusal, Option[Int]] =
      uow.transaction(sleepService.setGoal(_, accountId, targetMinutes))

  object stats:
    def medication(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MedicationPeriodView] =
      uow.transaction(statsService.medication(_, accountId, period))
    def hydration(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, HydrationPeriodView] =
      uow.transaction(statsService.hydration(_, accountId, period))
    def mood(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, MoodPeriodView] =
      uow.transaction(statsService.mood(_, accountId, period))
    def summary(accountId: UUID, period: StatsPeriod): Either[StatsRefusal, PeriodSummary] =
      uow.transaction(statsService.summary(_, accountId, period))
    def waterGoalAndMood(accountId: UUID, window: ComparisonWindow): Either[StatsRefusal, Comparison] =
      uow.transaction(statsService.waterGoalAndMood(_, accountId, window))

  object consents:
    def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, taps: ConsentTaps): Boolean =
      uow.transaction(_.consents.grant(accountId, purpose, notice, taps))
    def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean =
      uow.transaction(_.consents.withdraw(accountId, purpose, at))

object TransactionalTracking:

  /** A unit of work whose ledger has every purpose granted, for the suites that are not about consent. */
  def everyPurposeGranted(inner: UnitOfWork): UnitOfWork = new UnitOfWork:
    override def transaction[A](f: Tx => A): A = inner.transaction: tx =>
      f(new Tx:
        export tx.{consents as _, *}
        override val consents: ConsentLedger = new ConsentLedger:
          override def isGranted(accountId: UUID, purpose: ConsentPurpose): Boolean = true
          override def grant(accountId: UUID, purpose: ConsentPurpose, notice: ConsentNotice, taps: ConsentTaps): Boolean =
            false
          override def withdraw(accountId: UUID, purpose: ConsentPurpose, at: Instant): Boolean = false
          override def granted(accountId: UUID): List[GrantedConsent] = Nil
      )
