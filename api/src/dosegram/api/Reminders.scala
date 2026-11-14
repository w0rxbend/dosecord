package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import dosegram.contracts.HhMm
import dosegram.core.application.reminders.ReminderRefusal
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.TimelineItem
import dosegram.core.domain.QuietHours
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.PromptCopy
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.DaySet
import dosegram.core.domain.reminders.MedicationPresetParams
import dosegram.core.domain.reminders.MedicationPresets
import dosegram.core.domain.reminders.NudgePlan
import dosegram.core.domain.reminders.PromptKind
import dosegram.core.domain.reminders.PromptPreset
import dosegram.core.domain.reminders.PromptPresets
import dosegram.core.domain.reminders.TimeRef
import dosegram.core.domain.reminders.WorkHours
import dosegram.core.ports.Clock
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.Tx
import dosegram.core.ports.UnitOfWork
import sttp.tapir.Schema

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** The day of the user as the Reminders screen edits it. Times are `HH:MM`; `workDays` are ISO numbers, Monday 1. */
final case class DayProfileView(
    version: Long,
    wake: String,
    breakfast: String,
    lunch: String,
    dinner: String,
    bedtime: String,
    workDays: List[Int],
    workStart: String,
    workEnd: String,
    quietStart: String,
    quietEnd: String,
    saved: Boolean = false
) derives ReadWriter,
      Schema

final case class DayProfileInput(
    wake: String,
    breakfast: String,
    lunch: String,
    dinner: String,
    bedtime: String,
    workDays: List[Int],
    workStart: String,
    workEnd: String,
    quietStart: String,
    quietEnd: String
) derives ReadWriter,
      Schema

/** One prompt kind: its plan when one is on, and whether this kind can be turned on at all yet. */
final case class ReminderKindView(
    kind: String,
    name: String,
    available: Boolean,
    presetId: Option[String] = None,
    presetName: Option[String] = None,
    nextAt: Option[Instant] = None
) derives ReadWriter,
      Schema

final case class RemindersView(
    timeZone: String,
    kinds: List[ReminderKindView],
    medicationSchedules: Int,
    day: DayProfileView,
    pausedUntil: Option[Instant] = None
) derives ReadWriter,
      Schema

/** A preset with the next times it would fall due with the user's current day; nothing is saved by reading it. */
final case class PromptPresetView(id: String, kind: String, name: String, available: Boolean, preview: List[Instant])
    derives ReadWriter,
      Schema

/** A medication schedule preset as a draft for the schedule editor; absent when the engine does not remind it yet. */
final case class MedicationPresetView(
    id: String,
    name: String,
    available: Boolean,
    preview: List[Instant],
    draft: Option[ScheduleDraft] = None
) derives ReadWriter,
      Schema

final case class ReminderPresets(prompts: List[PromptPresetView], medication: List[MedicationPresetView])
    derives ReadWriter,
      Schema

/** Turns a preset on. A custom water interval also names `everyMinutes`, `from` and `until`, and optionally days. */
final case class ReminderPlanInput(
    presetId: String,
    everyMinutes: Option[Int] = None,
    from: Option[String] = None,
    until: Option[String] = None,
    weekdays: List[Int] = Nil
) derives ReadWriter,
      Schema

/** Pauses every prompt for `hours` (1 to 24); 0 resumes. Medication reminders are not affected. */
final case class ReminderPauseInput(hours: Int) derives ReadWriter, Schema

/** One reminder of the timeline. `kind` is a prompt kind or `medication`; no medication name is part of it. */
final case class ReminderTimelineEntry(at: Instant, kind: String, name: String) derives ReadWriter, Schema

final case class ReminderTimeline(date: LocalDate, entries: List[ReminderTimelineEntry]) derives ReadWriter, Schema

/** The reminders resources (ADR-023): the hub, the preset catalogue with previews, plans on and off, the day profile
  * and the pause of every prompt, and the timeline of one day across every domain. Every call is scoped to the
  * session's account; a plan of a domain without its consent is refused (API-19).
  */
final class Reminders(uow: UnitOfWork, clock: Clock, settings: ReminderSettings):
  import Reminders.*

  def view(accountId: UUID): RemindersView = uow.transaction(viewOf(_, accountId))

  def presets(accountId: UUID): ReminderPresets =
    uow.transaction: tx =>
      val profile = settings.profile(tx, accountId)
      val zone = settings.zone(tx, accountId)
      val now = clock.now()
      val prompts = PromptPresets.all.map: preset =>
        PromptPresetView(
          preset.id,
          preset.kind.id,
          PromptCopy.presetName(preset.id),
          PromptPresets.wired(preset.kind),
          settings.preview(tx, accountId, preset).map(_.at)
        )
      val medication = MedicationPresets.all.map: preset =>
        val rule = MedicationPresets.rule(preset, profile, MedicationPresetParams(), now.atZone(zone).toLocalDate)
        MedicationPresetView(
          preset.id,
          PromptCopy.presetName(preset.id),
          preset.materialised,
          MedicationPresets.preview(rule, zone, now, PreviewCount),
          Option.when(preset.materialised)(draftOf(rule))
        )
      ReminderPresets(prompts, medication)

  def apply(accountId: UUID, input: ReminderPlanInput): Either[ApiFailure, RemindersView] =
    presetOf(input).flatMap: preset =>
      uow.transaction: tx =>
        settings.apply(tx, accountId, preset) match
          case Right(_)                             => Right(viewOf(tx, accountId))
          case Left(ReminderRefusal.ConsentMissing) => Left(ApiFailure.consentRequired(purposeOf(preset.kind)))
          case Left(_)                              => Left(ApiFailure.invalid("presetId", "unavailable"))

  def turnOff(accountId: UUID, kind: String): Either[ApiFailure, RemindersView] =
    PromptKind
      .byId(kind)
      .toRight(ApiFailure.notFound())
      .map: found =>
        uow.transaction: tx =>
          settings.turnOff(tx, accountId, found)
          viewOf(tx, accountId)

  def pause(accountId: UUID, input: ReminderPauseInput): Either[ApiFailure, RemindersView] =
    if input.hours < 0 || input.hours > MaxPauseHours then Left(ApiFailure.invalid("hours", "out_of_range"))
    else
      uow.transaction: tx =>
        settings.pauseAll(tx, accountId, Option.when(input.hours > 0)(input.hours))
        Right(viewOf(tx, accountId))

  def day(accountId: UUID): DayProfileView =
    uow.transaction(tx => dayView(settings.profile(tx, accountId), isSaved(tx, accountId)))

  /** Whether the person ever saved a day profile; set-up offers it until then (MINI_APP S-19). */
  private def isSaved(tx: Tx, accountId: UUID): Boolean = tx.reminders.dayProfiles.anchors(accountId).isDefined

  def saveDay(accountId: UUID, version: Long, input: DayProfileInput): Either[ApiFailure, DayProfileView] =
    validated(input).flatMap: profile =>
      uow.transaction: tx =>
        val current = dayView(settings.profile(tx, accountId), isSaved(tx, accountId))
        for
          _ <- Either.cond(current.version == version, (), ApiFailure.versionConflict())
          stored <- settings.saveProfile(tx, accountId, profile).left.map(_ => ApiFailure.notFound())
        yield dayView(stored, saved = true)

  /** The reminders of one local day; without a date, tomorrow in the account zone. */
  def timeline(accountId: UUID, date: Option[LocalDate]): ReminderTimeline =
    uow.transaction: tx =>
      val day = date.getOrElse(clock.now().atZone(settings.zone(tx, accountId)).toLocalDate.plusDays(1))
      ReminderTimeline(day, settings.timeline(tx, accountId, day).map(entryOf))

  private def viewOf(tx: Tx, accountId: UUID): RemindersView =
    val hub = settings.hub(tx, accountId)
    val lines = hub.lines.map(line => line.kind -> line).toMap
    val kinds = PromptKind.values.toList.map: kind =>
      val line = lines.get(kind)
      ReminderKindView(
        kind.id,
        PromptCopy.kindName(kind),
        PromptPresets.wired(kind),
        line.flatMap(_.plan).map(_.presetId),
        line.flatMap(_.plan).map(p => PromptCopy.presetName(p.presetId)),
        line.flatMap(_.next)
      )
    RemindersView(
      hub.zone.getId,
      kinds,
      hub.medicationSchedules,
      dayView(hub.profile, isSaved(tx, accountId)),
      hub.pausedUntil
    )

object Reminders:

  private val PreviewCount = 5
  val MaxPauseHours = 24

  private def clock(t: LocalTime): String = PromptCopy.clock(t)

  def dayView(p: DayProfile, saved: Boolean): DayProfileView =
    val fields = List(
      clock(p.wake),
      clock(p.breakfast),
      clock(p.lunch),
      clock(p.dinner),
      clock(p.bedtime),
      p.work.days.toList.map(_.getValue).sorted.mkString(","),
      clock(p.work.start),
      clock(p.work.end),
      p.quiet.start.value,
      p.quiet.end.value
    )
    DayProfileView(
      ContentVersion.of(fields*),
      fields(0),
      fields(1),
      fields(2),
      fields(3),
      fields(4),
      p.work.days.toList.map(_.getValue).sorted,
      fields(6),
      fields(7),
      fields(8),
      fields(9),
      saved
    )

  /** Field by field, so the screen can name every wrong field at once. */
  def validated(input: DayProfileInput): Either[ApiFailure, DayProfile] =
    def time(field: String, value: String): Either[FieldError, LocalTime] =
      HhMm.parse(value).map(t => LocalTime.of(t.hour, t.minute)).left.map(_ => FieldError(field, "invalid"))
    val times = List(
      "wake" -> input.wake,
      "breakfast" -> input.breakfast,
      "lunch" -> input.lunch,
      "dinner" -> input.dinner,
      "bedtime" -> input.bedtime,
      "workStart" -> input.workStart,
      "workEnd" -> input.workEnd,
      "quietStart" -> input.quietStart,
      "quietEnd" -> input.quietEnd
    ).map(time.tupled)
    val days =
      if input.workDays.isEmpty || input.workDays.exists(d => d < 1 || d > 7) then
        Left(FieldError("workDays", "invalid"))
      else Right(input.workDays.map(DayOfWeek.of).toSet)
    val errors = (times ++ List(days)).flatMap(_.left.toOption)
    if errors.nonEmpty then Left(ApiFailure.invalid(errors))
    else
      val List(wake, breakfast, lunch, dinner, bedtime, workStart, workEnd, quietStart, quietEnd) =
        times.flatMap(_.toOption): @unchecked
      if !workStart.isBefore(workEnd) then Left(ApiFailure.invalid("workStart", "order"))
      else if quietStart == quietEnd then Left(ApiFailure.invalid("quietStart", "quiet_hours_empty"))
      else
        val quiet = QuietHours(HhMm.unsafe(clock(quietStart)), HhMm.unsafe(clock(quietEnd)))
        Right(
          DayProfile(wake, breakfast, lunch, dinner, bedtime, WorkHours(days.toOption.get, workStart, workEnd), quiet)
        )

  /** A catalogue preset, or the custom water interval with its window checked. */
  def presetOf(input: ReminderPlanInput): Either[ApiFailure, PromptPreset] =
    if input.presetId != PromptPresets.WaterCustomId then
      PromptPresets.byId(input.presetId).toRight(ApiFailure.invalid("presetId", "unknown"))
    else
      val from = input.from.flatMap(HhMm.parse(_).toOption).toRight(FieldError("from", "invalid"))
      val until = input.until.flatMap(HhMm.parse(_).toOption).toRight(FieldError("until", "invalid"))
      val every = input.everyMinutes
        .filter(m => m >= NudgePlan.MinIntervalMinutes && m <= NudgePlan.MaxIntervalMinutes)
        .toRight(FieldError("everyMinutes", "out_of_range"))
      val days =
        if input.weekdays.exists(d => d < 1 || d > 7) then Left(FieldError("weekdays", "invalid"))
        else Right(if input.weekdays.isEmpty then DaySet.Every else DaySet.Only(input.weekdays.map(DayOfWeek.of).toSet))
      (from, until, every, days) match
        case (Right(f), Right(u), Right(e), Right(d)) =>
          val plan: NudgePlan.Interval =
            NudgePlan.Interval(TimeRef.At(local(f)), TimeRef.At(local(u)), e, d, onlyWhenBehind = false)
          Right(PromptPresets.waterCustom(plan))
        case _ => Left(ApiFailure.invalid(List(from, until, every, days).flatMap(_.left.toOption)))

  private def local(t: HhMm): LocalTime = LocalTime.of(t.hour, t.minute)

  private def purposeOf(kind: PromptKind): ConsentPurpose =
    kind.consent.flatMap(ConsentPurpose.ofId).getOrElse(ConsentPurpose.Medication)

  private def entryOf(item: TimelineItem): ReminderTimelineEntry =
    item.kind match
      case Some(kind) =>
        ReminderTimelineEntry(item.at, kind.id, item.presetId.fold(PromptCopy.kindName(kind))(PromptCopy.presetName))
      case None => ReminderTimelineEntry(item.at, "medication", "Medication")

  /** The schedule editor's draft of a rule the engine reminds: fixed times on days, or as needed. */
  private def draftOf(rule: Rule): ScheduleDraft = rule match
    case Rule.FixedTimes(groups) =>
      ScheduleDraft(
        SchedulePattern.FixedTimes,
        times = groups.flatMap(_.times.map(_.value)).distinct.sorted,
        weekdays = groups.flatMap(_.days.map(_.ordinal + 1)).distinct.sorted
      )
    case Rule.AsNeeded(maxPerDay, minGap) =>
      ScheduleDraft(SchedulePattern.AsNeeded, maxPerDay = Some(maxPerDay), minGapMinutes = Some(minGap))
    case other => throw IllegalArgumentException(s"no draft for ${Rule.kindTag(other)}")
