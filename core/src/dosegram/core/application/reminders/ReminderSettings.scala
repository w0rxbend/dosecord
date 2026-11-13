package dosegram.core.application.reminders

import dosegram.core.domain.QuietHours
import dosegram.core.domain.reminders.Anchor
import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.NudgeSlot
import dosegram.core.domain.reminders.NudgeSlots
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.ports.AccountSettings
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.IdSource
import dosegram.core.ports.PromptSchedule
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.Tx

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Why a reminder setting changed nothing. */
enum ReminderRefusal:
  /** The prompt's domain has no consent: a plan is not stored before it (PRODUCT_SPEC R-IDC-9). */
  case ConsentMissing
  case NotWired
  case UnknownAccount
  case InvalidProfile(reason: String)

/** One prompt kind as the hub lists it: its plan, if any, and the next time it falls due. */
final case class HubLine(kind: PromptKind, plan: Option[PromptSchedule], next: Option[Instant])

/** What `/reminders` and the Reminders screen show. */
final case class HubView(
    zone: ZoneId,
    profile: DayProfile,
    lines: List[HubLine],
    medicationSchedules: Int,
    pausedUntil: Option[Instant]
)

/** The settings use cases of the prompts (ADR-023): the day profile, the preset catalogue with its preview, turning a
  * plan on and off, and the pauses. They run on the caller's transaction, like the tracking use cases.
  */
final class ReminderSettings(clock: Clock, ids: IdSource):

  import ReminderSettings.*

  def zone(tx: Tx, accountId: UUID): ZoneId = ZoneId.of(tx.accounts.timezoneOf(accountId).getOrElse("UTC"))

  /** The stored anchors with the account's quiet hours, or the defaults of each part the user never set. */
  def profile(tx: Tx, accountId: UUID): DayProfile =
    val anchors = tx.reminders.dayProfiles.anchors(accountId).getOrElse(DayAnchors.of(DayProfile.Default))
    anchors.withQuiet(quietOf(tx, accountId))

  def hub(tx: Tx, accountId: UUID): HubView =
    val now = clock.now()
    val zone = this.zone(tx, accountId)
    val profile = this.profile(tx, accountId)
    val plans = tx.reminders.prompts.schedules(accountId).filter(_.enabled).map(p => p.kind -> p).toMap
    val lines = PromptPresets.wired.toList
      .sortBy(_.ordinal)
      .map: kind =>
        val plan = plans.get(kind)
        HubLine(kind, plan, plan.flatMap(p => nextSlot(p, profile, zone, now)))
    HubView(zone, profile, lines, medicationSchedules(tx, accountId), activePause(tx, accountId, now))

  /** The next `limit` times a preset would fall due with the user's current day: shown before the preset is saved. */
  def preview(tx: Tx, accountId: UUID, preset: PromptPreset, limit: Int = 5): List[NudgeSlot] =
    NudgeSlots.upcoming(preset.plan, profile(tx, accountId), zone(tx, accountId), seedOf(accountId), clock.now(), limit)

  /** Turns the preset's plan on, replacing the plan of its kind. Slots before now are never decided for it. */
  def apply(tx: Tx, accountId: UUID, preset: PromptPreset): Either[ReminderRefusal, PromptSchedule] =
    if !PromptPresets.wired(preset.kind) then Left(ReminderRefusal.NotWired)
    else if !consented(tx, accountId, preset.kind) then Left(ReminderRefusal.ConsentMissing)
    else
      val now = clock.now()
      val existing = tx.reminders.prompts.schedule(accountId, preset.kind)
      val schedule = PromptSchedule(
        id = existing.fold(ids.next(now))(_.id),
        accountId = accountId,
        kind = preset.kind,
        presetId = preset.id,
        plan = preset.plan,
        ownCap = preset.ownCap,
        enabled = true,
        sound = existing.exists(_.sound),
        pausedUntil = None,
        activeFrom = now
      )
      Right(tx.reminders.prompts.save(schedule, now))

  def turnOff(tx: Tx, accountId: UUID, kind: PromptKind): Boolean =
    tx.reminders.prompts.setEnabled(accountId, kind, enabled = false, clock.now())

  /** Pauses every prompt for `hours`, or resumes them with `None`. Medication reminders are not affected. */
  def pauseAll(tx: Tx, accountId: UUID, hours: Option[Int]): Option[Instant] =
    val now = clock.now()
    val until = hours.map(h => now.plus(Duration.ofHours(h.toLong)))
    tx.reminders.dayProfiles.pauseAll(accountId, until, DayAnchors.of(DayProfile.Default), now)
    until

  def setAnchor(tx: Tx, accountId: UUID, anchor: Anchor, time: LocalTime): Either[ReminderRefusal, DayProfile] =
    saved(tx, accountId)(profile(tx, accountId).withAnchor(anchor, time))

  def setWork(
      tx: Tx,
      accountId: UUID,
      days: Set[DayOfWeek],
      start: LocalTime,
      end: LocalTime
  ): Either[ReminderRefusal, DayProfile] =
    saved(tx, accountId)(profile(tx, accountId).workHours(days, start, end))

  def setQuiet(tx: Tx, accountId: UUID, quiet: QuietHours): Either[ReminderRefusal, DayProfile] =
    tx.accounts.settingsOf(accountId) match
      case None           => Left(ReminderRefusal.UnknownAccount)
      case Some(settings) =>
        tx.accounts.saveSettings(accountId, settings.copy(quietHours = Some(quiet)), clock.now())
        Right(profile(tx, accountId))

  /** The prompts of `kind` wait until `until`: "Snooze 1 h" and "Pause for today" on a prompt. */
  def pauseKind(tx: Tx, scheduleId: UUID, until: Instant): Unit =
    tx.reminders.prompts.pause(scheduleId, Some(until), clock.now())

  /** The start of the next local day: the end of "Pause for today". */
  def endOfToday(zone: ZoneId): Instant =
    clock.now().atZone(zone).toLocalDate.plusDays(1).atStartOfDay(zone).toInstant

  def consented(tx: Tx, accountId: UUID, kind: PromptKind): Boolean =
    kind.consent.flatMap(ConsentPurpose.ofId).forall(tx.consents.isGranted(accountId, _))

  private def saved(tx: Tx, accountId: UUID)(changed: Either[String, DayProfile]): Either[ReminderRefusal, DayProfile] =
    changed match
      case Left(reason)   => Left(ReminderRefusal.InvalidProfile(reason))
      case Right(profile) =>
        tx.reminders.dayProfiles.save(accountId, DayAnchors.of(profile), clock.now())
        Right(profile)

  private def quietOf(tx: Tx, accountId: UUID): QuietHours =
    tx.accounts.settingsOf(accountId).getOrElse(AccountSettings.Default).quietHours.getOrElse(DayProfile.DefaultQuiet)

  private def activePause(tx: Tx, accountId: UUID, now: Instant): Option[Instant] =
    tx.reminders.dayProfiles.pausedUntil(accountId).filter(_.isAfter(now))

  private def medicationSchedules(tx: Tx, accountId: UUID): Int =
    tx.medications
      .listForAccount(accountId)
      .flatMap(m => tx.schedules.listForMedication(m.id))
      .count(_.status == ScheduleStatus.Active)

object ReminderSettings:

  /** The seed of a plan's random moments: stable per account, so a preview shows the times that will be used. */
  def seedOf(accountId: UUID): Long = accountId.getMostSignificantBits ^ accountId.getLeastSignificantBits

  def nextSlot(plan: PromptSchedule, profile: DayProfile, zone: ZoneId, now: Instant): Option[Instant] =
    val after = if plan.pausedUntil.exists(_.isAfter(now)) then plan.pausedUntil.get else now
    NudgeSlots.upcoming(plan.plan, profile, zone, seedOf(plan.accountId), after, limit = 1).headOption.map(_.at)
