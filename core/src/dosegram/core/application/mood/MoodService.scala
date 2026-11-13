package dosegram.core.application.mood

import dosegram.core.domain.EntryDay
import dosegram.core.domain.EntrySource
import dosegram.core.domain.VoidReason
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.MoodDay
import dosegram.core.domain.insights.MoodStats
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodBounds
import dosegram.core.domain.mood.MoodCheckin
import dosegram.core.domain.mood.Valence
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentCheck
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.TrackingTx
import dosegram.core.ports.TrackingUnitOfWork

import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** One check-in request. Valence is the only mandatory part; `clientKey` makes the same tap record at most once. */
final case class CheckIn(
    accountId: UUID,
    valence: Valence,
    energy: Option[Energy],
    tagIds: List[UUID],
    day: EntryDay,
    source: EntrySource,
    clientKey: String
)

/** Why a mood request changed nothing. No refusal depends on the value of a check-in. */
enum MoodRefusal:
  /** No active `mood` consent: nothing is stored and nothing is shown (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing
  case UnknownAccount
  case UnknownCheckin
  case UndoWindowPassed

  /** More than `limit` distinct tags on one check-in (R-MOO-3). */
  case TooManyTags(limit: Int)

/** A local day of one account: the day figures, absent on a day with no check-in, and the latest live check-in. */
final case class MoodDayView(date: LocalDate, zone: ZoneId, recorded: Option[MoodDay], latest: Option[MoodCheckin])

/** The outcome of a check-in or of its energy step. It has the same shape for every value (R-MOO-6). */
final case class MoodRecorded(checkin: MoodCheckin, day: MoodDayView, replayed: Boolean)

final case class MoodRemoved(day: MoodDayView, changed: Boolean)

/** The mood use cases (PRODUCT_SPEC section 8.9; ROADMAP G6.1, domain part). Every write and every read needs the
  * `mood` consent. The response to a check-in never depends on its value: no advice, concern or praise is derived from
  * it, and nothing here reads a note.
  */
final class MoodService(uow: TrackingUnitOfWork, consent: ConsentCheck, clock: Clock, ids: IdSource):

  def checkIn(command: CheckIn): Either[MoodRefusal, MoodRecorded] =
    val tags = command.tagIds.distinct
    if tags.size > MoodBounds.MaxTags then Left(MoodRefusal.TooManyTags(MoodBounds.MaxTags))
    else
      consented(command.accountId): (tx, zone) =>
        tx.mood.findByClientKey(command.accountId, command.clientKey) match
          case Some(existing) => Right(recorded(tx, existing, replayed = true))
          case None           =>
            val now = clock.now()
            val (date, occurredAt) = command.day.resolve(now, zone)
            val checkin = MoodCheckin(
              id = ids.next(now),
              accountId = command.accountId,
              occurredAt = occurredAt,
              localDate = date,
              zone = zone,
              valence = command.valence,
              energy = command.energy,
              tagIds = tags,
              source = command.source,
              clientKey = Some(command.clientKey),
              supersedesId = None,
              createdAt = now,
              voidedAt = None
            )
            Right(store(tx, checkin))

  /** The optional second step: the check-in is replaced by a copy that carries the energy. Entries are immutable, so
    * the old row is voided as superseded and the new row points at it.
    */
  def setEnergy(
      accountId: UUID,
      checkinId: UUID,
      energy: Energy,
      clientKey: String
  ): Either[MoodRefusal, MoodRecorded] =
    consented(accountId): (tx, _) =>
      tx.mood.findByClientKey(accountId, clientKey) match
        case Some(existing) => Right(recorded(tx, existing, replayed = true))
        case None           =>
          tx.mood.get(accountId, checkinId).filter(_.isLive) match
            case None           => Left(MoodRefusal.UnknownCheckin)
            case Some(original) => supersede(tx, original, energy, clientKey)

  private def supersede(
      tx: TrackingTx,
      original: MoodCheckin,
      energy: Energy,
      clientKey: String
  ): Either[MoodRefusal, MoodRecorded] =
    val now = clock.now()
    // The void is the fence: of two concurrent energy steps on one check-in, only the one that voids it may replace it.
    if !tx.mood.void(original.accountId, original.id, VoidReason.Superseded, now) then Left(MoodRefusal.UnknownCheckin)
    else
      val amended = original.copy(
        id = ids.next(now),
        energy = Some(energy),
        clientKey = Some(clientKey),
        supersedesId = Some(original.id),
        createdAt = now
      )
      Right(store(tx, amended))

  /** Undo on the outcome message: voids the check-in while it is younger than 15 minutes (R-MOO-10). */
  def undo(accountId: UUID, checkinId: UUID): Either[MoodRefusal, MoodRemoved] =
    consented(accountId): (tx, _) =>
      val now = clock.now()
      tx.mood.get(accountId, checkinId) match
        case None                             => Left(MoodRefusal.UnknownCheckin)
        case Some(checkin) if !checkin.isLive =>
          Right(MoodRemoved(dayView(tx, accountId, checkin.localDate, checkin.zone), changed = false))
        case Some(checkin) if Duration.between(checkin.createdAt, now).compareTo(MoodBounds.UndoWindow) > 0 =>
          Left(MoodRefusal.UndoWindowPassed)
        case Some(checkin) =>
          val changed = tx.mood.void(accountId, checkinId, VoidReason.Undone, now)
          Right(MoodRemoved(dayView(tx, accountId, checkin.localDate, checkin.zone), changed))

  def day(accountId: UUID, day: EntryDay): Either[MoodRefusal, MoodDayView] =
    consented(accountId): (tx, zone) =>
      val (date, _) = day.resolve(clock.now(), zone)
      Right(dayView(tx, accountId, date, zone))

  private def consented[A](accountId: UUID)(f: (TrackingTx, ZoneId) => Either[MoodRefusal, A]): Either[MoodRefusal, A] =
    if !consent.isGranted(accountId, ConsentPurpose.Mood) then Left(MoodRefusal.ConsentMissing)
    else
      uow.transaction: tx =>
        tx.accountZone(accountId) match
          case Some(zone) => f(tx, zone)
          case None       => Left(MoodRefusal.UnknownAccount)

  private def store(tx: TrackingTx, checkin: MoodCheckin): MoodRecorded =
    if tx.mood.insert(checkin) then recorded(tx, checkin, replayed = false)
    else
      // A concurrent request with the same client key won the insert: answer with its check-in.
      val winner = checkin.clientKey
        .flatMap(tx.mood.findByClientKey(checkin.accountId, _))
        .getOrElse(throw IllegalStateException(s"mood check-in ${checkin.id} conflicted without a client-key twin"))
      recorded(tx, winner, replayed = true)

  private def recorded(tx: TrackingTx, checkin: MoodCheckin, replayed: Boolean): MoodRecorded =
    MoodRecorded(checkin, dayView(tx, checkin.accountId, checkin.localDate, checkin.zone), replayed)

  private def dayView(tx: TrackingTx, accountId: UUID, date: LocalDate, zone: ZoneId): MoodDayView =
    val checkins = tx.mood.listLive(accountId, DateRange.single(date))
    MoodDayView(date, zone, MoodStats.byDay(checkins).get(date), checkins.maxByOption(_.occurredAt.toEpochMilli))
end MoodService
