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
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.Tx

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

/** The optional second step of a check-in: the energy for `checkinId`. `clientKey` is the idempotency key of the tap.
  */
final case class SetEnergy(accountId: UUID, checkinId: UUID, energy: Energy, clientKey: String)

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
  * it, and nothing here reads a note. Every use case runs on the caller's transaction, like the hydration use cases.
  */
final class MoodService(clock: Clock, ids: IdSource):

  def checkIn(tx: Tx, command: CheckIn): Either[MoodRefusal, MoodRecorded] =
    val tags = command.tagIds.distinct
    if tags.size > MoodBounds.MaxTags then Left(MoodRefusal.TooManyTags(MoodBounds.MaxTags))
    else
      consented(tx, command.accountId): zone =>
        tx.tracking.mood.findByClientKey(command.accountId, command.clientKey) match
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
  def setEnergy(tx: Tx, command: SetEnergy): Either[MoodRefusal, MoodRecorded] =
    consented(tx, command.accountId): _ =>
      tx.tracking.mood.findByClientKey(command.accountId, command.clientKey) match
        case Some(existing) => Right(recorded(tx, existing, replayed = true))
        case None           =>
          tx.tracking.mood.get(command.accountId, command.checkinId).filter(_.isLive) match
            case None           => Left(MoodRefusal.UnknownCheckin)
            case Some(original) => supersede(tx, original, command.energy, command.clientKey)

  private def supersede(
      tx: Tx,
      original: MoodCheckin,
      energy: Energy,
      clientKey: String
  ): Either[MoodRefusal, MoodRecorded] =
    val now = clock.now()
    // The void is the fence: of two concurrent energy steps on one check-in, only the one that voids it may replace it.
    if !tx.tracking.mood.void(original.accountId, original.id, VoidReason.Superseded, now) then
      Left(MoodRefusal.UnknownCheckin)
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
  def undo(tx: Tx, accountId: UUID, checkinId: UUID): Either[MoodRefusal, MoodRemoved] =
    consented(tx, accountId): _ =>
      val now = clock.now()
      tx.tracking.mood.get(accountId, checkinId) match
        case None                             => Left(MoodRefusal.UnknownCheckin)
        case Some(checkin) if !checkin.isLive =>
          Right(MoodRemoved(dayView(tx, accountId, checkin.localDate, checkin.zone), changed = false))
        case Some(checkin) if Duration.between(checkin.createdAt, now).compareTo(MoodBounds.UndoWindow) > 0 =>
          Left(MoodRefusal.UndoWindowPassed)
        case Some(checkin) =>
          val changed = tx.tracking.mood.void(accountId, checkinId, VoidReason.Undone, now)
          Right(MoodRemoved(dayView(tx, accountId, checkin.localDate, checkin.zone), changed))

  def day(tx: Tx, accountId: UUID, day: EntryDay): Either[MoodRefusal, MoodDayView] =
    consented(tx, accountId): zone =>
      val (date, _) = day.resolve(clock.now(), zone)
      Right(dayView(tx, accountId, date, zone))

  private def consented[A](tx: Tx, accountId: UUID)(f: ZoneId => Either[MoodRefusal, A]): Either[MoodRefusal, A] =
    if !tx.consents.isGranted(accountId, ConsentPurpose.Mood) then Left(MoodRefusal.ConsentMissing)
    else
      tx.accounts.timezoneOf(accountId) match
        case Some(zone) => f(ZoneId.of(zone))
        case None       => Left(MoodRefusal.UnknownAccount)

  private def store(tx: Tx, checkin: MoodCheckin): MoodRecorded =
    if tx.tracking.mood.insert(checkin) then recorded(tx, checkin, replayed = false)
    else
      // A concurrent request with the same client key won the insert: answer with its check-in.
      val winner = checkin.clientKey
        .flatMap(tx.tracking.mood.findByClientKey(checkin.accountId, _))
        .getOrElse(throw IllegalStateException(s"mood check-in ${checkin.id} conflicted without a client-key twin"))
      recorded(tx, winner, replayed = true)

  private def recorded(tx: Tx, checkin: MoodCheckin, replayed: Boolean): MoodRecorded =
    MoodRecorded(checkin, dayView(tx, checkin.accountId, checkin.localDate, checkin.zone), replayed)

  private def dayView(tx: Tx, accountId: UUID, date: LocalDate, zone: ZoneId): MoodDayView =
    val checkins = tx.tracking.mood.listLive(accountId, DateRange.single(date))
    MoodDayView(date, zone, MoodStats.byDay(checkins).get(date), checkins.maxByOption(_.occurredAt.toEpochMilli))
end MoodService
