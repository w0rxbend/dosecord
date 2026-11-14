package dosegram.api

import dosegram.core.application.mood.CheckIn
import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.copy.CheckInCopy
import dosegram.core.domain.copy.MoodLabels
import dosegram.core.domain.copy.tr
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork

import java.time.LocalDate
import java.util.UUID

/** The mood resources (ARCHITECTURE section 9.5): the scale, the blob geometry, check-ins and the days of a range.
  * Every route needs the `mood` consent (API-19). An answer has the same shape for every value: nothing here reads a
  * check-in to decide what to say (PRODUCT_SPEC R-MOO-6, MINI_APP MA-MOOD-7).
  *
  * @param blob
  *   the drawn states. They are the renderer's geometry, built by the composition root: this module holds no copy of
  *   the formula (MINI_APP section 7.9).
  */
final class Mood(uow: UnitOfWork, tracking: TrackingUseCases, blob: MoodBlobStates):
  import Mood.*

  def scale(accountId: UUID): Either[ApiFailure, MoodScale] =
    uow.transaction(todayOf(_, accountId)).map(_ => Scale.copy(blobRevision = blob.revision))

  /** The blob states of the current revision; another revision does not exist. */
  def blobStates(accountId: UUID, revision: Option[String]): Either[ApiFailure, MoodBlobStates] =
    uow
      .transaction(todayOf(_, accountId))
      .flatMap(_ => Either.cond(revision.forall(_ == blob.revision), blob, ApiFailure.notFound()))

  /** The live check-ins of a range of local days, oldest first; without a range, those of today. */
  def checkins(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, MoodCheckins] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.single(today.date))
      yield MoodCheckins(range.start, range.end, tx.tracking.mood.listLive(accountId, range).map(checkinView))

  /** Records a check-in of now. `key` is the idempotency key of the request: the same key records once. */
  def checkIn(accountId: UUID, key: UUID, input: MoodCheckinInput): Either[ApiFailure, MoodRecordedView] =
    for
      valence <- Valence.of(input.valence).toRight(ApiFailure.invalid("valence", "out_of_range"))
      energy <- energyOf(input.energy)
      command = CheckIn(accountId, valence, energy, Nil, EntryDay.Today, EntrySource.MiniApp, s"api:$key")
      recorded <- uow.transaction(tracking.mood.checkIn(_, command)).left.map(Refusals.of)
    yield MoodRecordedView(checkinView(recorded.checkin), dayView(recorded.day))

  /** Voids a check-in of any age and answers with its day. A second delete changes nothing and answers the same. */
  def remove(accountId: UUID, checkinId: UUID): Either[ApiFailure, MoodDayView] =
    uow
      .transaction(tracking.mood.delete(_, accountId, checkinId))
      .left
      .map(Refusals.of)
      .map(removed => dayView(removed.day))

  /** Today and one drawn state per day of a range; without a range, the last seven days (MA-MOOD-8). */
  def summary(accountId: UUID, from: Option[LocalDate], to: Option[LocalDate]): Either[ApiFailure, MoodSummary] =
    uow.transaction: tx =>
      for
        today <- todayOf(tx, accountId)
        range <- Ranges.of(from, to, DateRange.endingOn(today.date, DefaultSummaryDays))
        days <- tracking.stats.moodCalendar(tx, accountId, range).left.map(Refusals.of)
      yield MoodSummary(
        range.start,
        range.end,
        dayView(today),
        range.dates.map(date => summaryDay(date, days.get(date)))
      )

  /** Today in the account zone; a refusal here is the consent gate of every read. */
  private def todayOf(tx: Tx, accountId: UUID): Either[ApiFailure, dosegram.core.application.mood.MoodDayView] =
    tracking.mood.day(tx, accountId, EntryDay.Today).left.map(Refusals.of)

object Mood:
  private val DefaultSummaryDays = 7

  private val Scale: MoodScale = MoodScale(
    valence = Valence.steps.map(step => MoodStop(step.step, MoodLabels.valence(step))),
    energy = Energy.steps.map(step => MoodStop(step.step, MoodLabels.energy(step))),
    blobRevision = "",
    statement = ApiCopy.moodStatement
  )

  private def energyOf(step: Option[Int]): Either[ApiFailure, Option[Energy]] = step match
    case None       => Right(None)
    case Some(step) => Energy.of(step).map(Some(_)).toRight(ApiFailure.invalid("energy", "out_of_range"))

  def checkinView(checkin: MoodCheckin): MoodCheckinView =
    MoodCheckinView(
      id = checkin.id,
      date = checkin.localDate,
      time = ApiCopy.clock(checkin.occurredAt, checkin.zone),
      occurredAt = checkin.occurredAt,
      valence = checkin.valence.step,
      label = CheckInCopy.label(checkin.valence, checkin.energy),
      blobState = BlobStates.of(checkin.valence, checkin.energy),
      energy = checkin.energy.map(_.step)
    )

  /** A day by its latest check-in. A day without one is `blob.none`: no other day's mood stands in for it (VA-BL-02).
    */
  def dayView(view: dosegram.core.application.mood.MoodDayView): MoodDayView =
    MoodDayView(
      date = view.date,
      checkIns = view.recorded.fold(0)(_.n),
      text = view.latest.fold(ApiCopy.noCheckIn)(latest => CheckInCopy.label(latest.valence, latest.energy)),
      blobState = view.latest.fold(BlobStates.NoEntry)(latest => BlobStates.of(latest.valence, latest.energy)),
      latest = view.latest.map(checkinView)
    )

  /** The state of a daily mean: its quantised level, at the rounded mean of the energy steps that were given. */
  def blobStateOf(day: MoodDay): String =
    BlobStates.of(day.level, day.energyMean.map(mean => Math.round(mean).toInt))

  private def summaryDay(date: LocalDate, recorded: Option[MoodDay]): MoodSummaryDay =
    val value = recorded.fold(tr("no check-in", "немає позначки"))(day =>
      s"${ApiCopy.moodMean(day)}, ${ApiCopy.count(day.n, "check-in")}"
    )
    MoodSummaryDay(
      date = date,
      label = ApiCopy.weekday(date),
      name = s"${ApiCopy.dayLabel(date)}: $value",
      blobState = recorded.fold(BlobStates.NoEntry)(blobStateOf),
      checkIns = recorded.fold(0)(_.n)
    )
