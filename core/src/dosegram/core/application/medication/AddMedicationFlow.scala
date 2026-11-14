package dosegram.core.application.medication

import dosegram.contracts.*
import dosegram.core.application.capture.MedicationConsentGate
import dosegram.core.application.exports.RecordSummaries
import dosegram.core.application.identity.AccountCreateFlow
import dosegram.core.chat.Flow
import dosegram.core.chat.FlowContext
import dosegram.core.chat.Step
import dosegram.core.chat.StepInput
import dosegram.core.chat.StepKind
import dosegram.core.chat.StepTransition
import dosegram.core.domain.ReminderPolicy
import dosegram.core.domain.Rule
import dosegram.core.domain.SlotGroup
import dosegram.core.domain.copy.ConsentCopy
import dosegram.core.domain.copy.IdentityCopy
import dosegram.core.domain.copy.InputCopy
import dosegram.core.domain.copy.Labels
import dosegram.core.domain.copy.MedicationDepthCopy
import dosegram.core.domain.copy.WizardCopy
import dosegram.core.domain.input.DayList
import dosegram.core.domain.input.DoseText
import dosegram.core.domain.input.TimeOfDay
import dosegram.core.domain.reminders.DayAnchors
import dosegram.core.domain.reminders.DayProfile
import dosegram.core.domain.reminders.MedicationPreset
import dosegram.core.domain.reminders.MedicationPresetParams
import dosegram.core.domain.reminders.MedicationPresets
import dosegram.core.ports.Clock
import dosegram.core.ports.ScheduleStatus
import dosegram.core.ports.Tx
import dosegram.core.scheduling.CreateSchedule
import dosegram.core.scheduling.ScheduleLifecycle
import upickle.default.read
import upickle.default.write

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.UUID

/** The add-medication wizard (ROADMAP M1.9, R11/R13/R17/R18; flow per docs/MEDICATION_REMINDER_UX.md): a declarative
  * `Flow` over the M0.12b engine.
  *
  *   - step 1 `Form(name, dose)` — two FormRunner questions.
  *   - the times step (BOT_UX section 15.6): quick times `[08:00][09:00][13:00][20:00][22:00][Other time][As needed]`,
  *     or one or more times typed in any spelling the shared grammar reads (`TimeOfDay`); a time that is not understood
  *     is named back with examples and the step stays open; two failures at `[Other time]` return to the buttons.
  *   - the instructions step echoes the times understood (`[Change times]` goes back) and takes optional text.
  *   - step 2 (only when times were given) days `[Every day][Weekdays][Choose days...]`, the multi-select day picker
  *     behind the last.
  *   - step 3 one confirm card `Vitamin D 1000 IU, every day 09:00 Europe/Kyiv [Create][Change days][Change timezone]`
  *     (as-needed cards skip Change days); its Cancel is the one of the nav row.
  *
  * `[Back]`/`[Cancel]` are on every step (the engine's nav row) and `/cancel` aborts; nothing writes a row before the
  * confirm. Find-or-create on `name_norm` (R18): re-adding a tracked name shows the offer on the confirm card, and its
  * `[Update]` replaces the existing schedule through a new revision (the full medication-profile edit is M3.2, so the
  * dose snapshot of the kept medication stays as recorded). The schedule zone defaults to the account timezone;
  * `[Change timezone]` overrides it for this schedule.
  */
object AddMedicationFlow:

  val Id = "medication.add"

  // Session-data keys; the form field keys double as session keys, which is what lets the engine pre-fill a
  // re-rendered form from the session data.
  private val NameKey = "name"
  private val DoseKey = "dose"
  private val TimesKey = "times"
  private val InstructionsKey = "instructions"
  private val DaysKey = "days"
  private val TimezoneKey = "timezone"
  private val ExistingKey = "existing"
  private val TzErrorKey = "tz_error"
  private val PresetKey = "preset"
  private val RuleKey = "rule"
  private val PreviewKey = "preview"

  // Step choice keys.
  private val EveryDayKey = "every_day"
  private val WeekdaysKey = "weekdays"
  private val ChooseDaysKey = "choose_days"
  private val CreateKey = "create"
  private val UpdateKey = "update"
  private val ChangeDaysKey = "change_days"
  private val ChangeTimezoneKey = "change_tz"
  private val TzOtherKey = "tz.other"
  private val QuickTimePrefix = "time."
  private val OtherTimeKey = "times.other"
  private val AsNeededKey = "times.as_needed"
  private val NoInstructionsKey = "instructions.none"
  private val ChangeTimesKey = "times.change"
  private val PresetsKey = "times.presets"
  private val PreviewCount = 3

  private val allDays: List[Weekday] = Weekday.values.toList
  private val weekDays: List[Weekday] = allDays.take(5)

  val formId = "medication.add"

  /** The step 1 form; pinned by the suite B1 form golden (via `GoldenScenarios`). */
  def formFields: List[Field] = List(
    Field(NameKey, WizardCopy.fieldName, FieldType.Text, placeholder = Some(WizardCopy.namePlaceholder)),
    Field(
      DoseKey,
      WizardCopy.fieldDose,
      FieldType.Text,
      required = false,
      placeholder = Some(WizardCopy.dosePlaceholder)
    )
  )

  /** The times as the session keeps them: canonical `HH:MM, HH:MM`; empty means as-needed. */
  def parseTimes(text: String): List[HhMm] =
    if text.trim.isEmpty then Nil else TimeOfDay.parseList(text).getOrElse(Nil)

  /** "1000 IU" -> (Some(1000), Some("IU")); the shared dose grammar (`DoseText`). */
  def parseDose(text: String): (Option[BigDecimal], Option[String]) = DoseText.parse(text)

  private def normalizeName(name: String): String = name.trim.toLowerCase(Locale.ROOT)

  private def isDayKey(key: String): Boolean = allDays.exists(_.toString == key)

  private def dayKeys(days: List[Weekday]): String = days.map(_.toString).mkString(",")

  /** Days in canonical Monday-first order. */
  private def canonicalDays(keys: List[String]): List[String] =
    keys.distinct.map(Weekday.valueOf).sortBy(_.ordinal).map(_.toString)

  private def daysOf(data: Map[String, String]): List[Weekday] =
    data.get(DaysKey) match
      case Some(value) if value.nonEmpty => value.split(",").toList.map(Weekday.valueOf)
      case _                             => allDays

  /** "1 3 5", "Mon, Wed", "weekdays" or "mon-fri" on the text tiers (the shared `DayList` grammar). */
  def parseDaySelection(text: String): Either[String, List[String]] =
    DayList.parse(text).left.map(InputCopy.days).map(_.map(_.toString))

  private def quickTimeOptions: List[(String, String)] =
    WizardCopy.quickTimes.map(t => s"$QuickTimePrefix$t" -> t) ++
      List(
        OtherTimeKey -> WizardCopy.otherTime,
        AsNeededKey -> WizardCopy.asNeeded,
        PresetsKey -> MedicationDepthCopy.presetsLabel
      )

  /** Every preset of the catalogue that the engine reminds, as quick-tap choices (supervisor decision, 2026-11-14). */
  private def presetOptions: List[(String, String)] =
    MedicationPresets.all.filter(_.materialised).map(preset => preset.id -> MedicationDepthCopy.presetButton(preset.id))

  private def presetOf(data: Map[String, String]): Option[MedicationPreset] =
    data.get(PresetKey).filter(_.nonEmpty).flatMap(MedicationPresets.byId)

  /** Typed times: the canonical list for the session, or the reply that names what was not understood. */
  private def typedTimes(text: String): Either[String, StepTransition] =
    TimeOfDay
      .parseList(text)
      .left
      .map(InputCopy.time)
      .map(times =>
        StepTransition.Next("instructions", Map(TimesKey -> times.map(_.value).mkString(", "), PresetKey -> ""))
      )

  private def timesOf(data: Map[String, String]): List[String] =
    parseTimes(data.getOrElse(TimesKey, "")).map(_.value)

  private def afterInstructions(data: Map[String, String]): String =
    if timesOf(data).isEmpty || presetOf(data).isDefined then "confirm" else "days"

  private def timesOrPresetEcho(data: Map[String, String]): String =
    presetOf(data).fold(WizardCopy.timesEcho(timesOf(data))): preset =>
      MedicationDepthCopy.presetEcho(MedicationDepthCopy.presetButton(preset.id))

  def flow(clock: Clock, lifecycle: ScheduleLifecycle): Flow = Flow(
    id = Id,
    firstStep = "form",
    steps = Map(
      "form" -> Step(
        id = "form",
        kind = StepKind.Form(formId, WizardCopy.addMedicationTitle, formFields),
        render = _ => List(paragraph(WizardCopy.addMedicationIntro)),
        accept = {
          case (StepInput.FormAnswered(fields), _) =>
            val name = fields.getOrElse(NameKey, "").trim
            if name.isEmpty then Left(WizardCopy.nameRequired)
            else
              Right(
                StepTransition.Next(
                  "times",
                  Map(
                    NameKey -> name,
                    DoseKey -> fields.getOrElse(DoseKey, "").trim,
                    // Cleared on every form submit; the confirm step's onEnter recomputes the offer.
                    ExistingKey -> ""
                  )
                )
              )
          case _ => Left(WizardCopy.nameRequired)
        }
      ),
      "times" -> Step(
        id = "times",
        kind = StepKind.Choices(_ => quickTimeOptions, acceptsText = true),
        render = _ => List(paragraph(WizardCopy.timesPrompt)),
        accept = {
          case (StepInput.Chosen(OtherTimeKey), _) => Right(StepTransition.Next("times_other"))
          case (StepInput.Chosen(PresetsKey), _)   => Right(StepTransition.Next("presets"))
          case (StepInput.Chosen(AsNeededKey), _)  =>
            Right(StepTransition.Next("instructions", Map(TimesKey -> "", PresetKey -> "")))
          case (StepInput.Chosen(key), _) if key.startsWith(QuickTimePrefix) =>
            typedTimes(key.drop(QuickTimePrefix.length))
          case (StepInput.TextEntered(text), _) => typedTimes(text)
          case _                                => Left(InputCopy.time(TimeOfDay.Rejection.Empty))
        }
      ),
      "presets" -> Step(
        id = "presets",
        kind = StepKind.Choices(_ => presetOptions),
        render = _ => List(paragraph(MedicationDepthCopy.presetsPrompt)),
        accept = {
          case (StepInput.Chosen(MedicationPresets.AsNeeded.id), _) =>
            Right(StepTransition.Next("instructions", Map(TimesKey -> "", PresetKey -> "")))
          case (StepInput.Chosen(id), _) if presetOptions.exists(_._1 == id) =>
            // The times come from the day profile when the confirm card is entered; until then the preset stands
            // for them.
            Right(StepTransition.Next("instructions", Map(PresetKey -> id, TimesKey -> "", DaysKey -> "")))
          case _ => Left(MedicationDepthCopy.presetsPrompt)
        }
      ),
      "times_other" -> Step(
        id = "times_other",
        kind = StepKind.Text,
        render = _ => List(paragraph(WizardCopy.timesOtherPrompt)),
        accept = {
          case (StepInput.TextEntered(text), _) => typedTimes(text)
          case _                                => Left(InputCopy.time(TimeOfDay.Rejection.Empty))
        },
        rescue = Some("times")
      ),
      "instructions" -> Step(
        id = "instructions",
        kind = StepKind.Choices(
          _ => List(NoInstructionsKey -> WizardCopy.noInstructions, ChangeTimesKey -> WizardCopy.changeTimes),
          acceptsText = true
        ),
        render = data => List(paragraph(timesOrPresetEcho(data)), paragraph(WizardCopy.instructionsPrompt)),
        accept = {
          case (StepInput.Chosen(ChangeTimesKey), _)       => Right(StepTransition.Next("times"))
          case (StepInput.Chosen(NoInstructionsKey), data) =>
            Right(StepTransition.Next(afterInstructions(data), Map(InstructionsKey -> "")))
          case (StepInput.TextEntered(text), data) =>
            Right(StepTransition.Next(afterInstructions(data), Map(InstructionsKey -> text.trim)))
          case _ => Left(WizardCopy.instructionsPrompt)
        }
      ),
      "days" -> Step(
        id = "days",
        kind = StepKind.choices(
          List(
            EveryDayKey -> WizardCopy.everyDay,
            WeekdaysKey -> WizardCopy.weekdays,
            ChooseDaysKey -> WizardCopy.chooseDays
          )
        ),
        render = _ => List(paragraph(WizardCopy.daysPrompt)),
        accept = {
          case (StepInput.Chosen(EveryDayKey), _) =>
            Right(StepTransition.Next("confirm", Map(DaysKey -> dayKeys(allDays))))
          case (StepInput.Chosen(WeekdaysKey), _) =>
            Right(StepTransition.Next("confirm", Map(DaysKey -> dayKeys(weekDays))))
          case (StepInput.Chosen(ChooseDaysKey), _) => Right(StepTransition.Next("days_custom"))
          case _                                    => Left(WizardCopy.invalidDays)
        }
      ),
      "days_custom" -> Step(
        id = "days_custom",
        kind = StepKind.Choices(
          _ => WizardCopy.weekdayLabels.map(label => label -> label),
          minSelect = 1,
          maxSelect = 7
        ),
        render = _ => List(paragraph(WizardCopy.daysPrompt)),
        accept = {
          case (StepInput.ChosenMany(keys), _) if keys.nonEmpty && keys.forall(isDayKey) =>
            Right(StepTransition.Next("confirm", Map(DaysKey -> canonicalDays(keys).mkString(","))))
          // A single tap on the picker selects one day.
          case (StepInput.Chosen(key), _) if isDayKey(key) =>
            Right(StepTransition.Next("confirm", Map(DaysKey -> key)))
          case (StepInput.TextEntered(text), _) =>
            parseDaySelection(text).map(days => StepTransition.Next("confirm", Map(DaysKey -> days.mkString(","))))
          case _ => Left(WizardCopy.invalidDays)
        }
      ),
      "tz" -> Step(
        id = "tz",
        kind = StepKind.Choices(_ => AccountCreateFlow.pickerOptions(clock.now())),
        render = data =>
          data.get(TzErrorKey).filter(_.nonEmpty).map(paragraph).toList ++
            List(paragraph(IdentityCopy.timezonePrompt)),
        accept = {
          case (StepInput.Chosen(TzOtherKey), _) => Right(StepTransition.Next("tz_custom"))
          case (StepInput.Chosen(key), _) if AccountCreateFlow.pickerZones.exists(_.getId == key) =>
            Right(StepTransition.Next("confirm", Map(TimezoneKey -> key, TzErrorKey -> "")))
          case _ => Left(IdentityCopy.invalidZone)
        }
      ),
      "tz_custom" -> Step(
        id = "tz_custom",
        kind = StepKind.Text,
        render = _ => List(paragraph(IdentityCopy.timezoneCustomPrompt)),
        accept = {
          case (StepInput.TextEntered(text), _) =>
            AccountCreateFlow.parseZone(text, clock.now()) match
              case Some(zone) =>
                Right(StepTransition.Next("confirm", Map(TimezoneKey -> zone.getId, TzErrorKey -> "")))
              case None => Right(StepTransition.Next("tz", Map(TzErrorKey -> IdentityCopy.zoneNotFound(text))))
          case _ => Left(IdentityCopy.invalidZone)
        }
      ),
      "confirm" -> Step(
        id = "confirm",
        kind = StepKind.Choices(confirmOptions),
        render = confirmRender,
        accept = {
          case (StepInput.Chosen(CreateKey | UpdateKey), _) => Right(StepTransition.Complete)
          case (StepInput.Chosen(ChangeDaysKey), _)         => Right(StepTransition.Next("days"))
          case (StepInput.Chosen(ChangeTimezoneKey), _)     => Right(StepTransition.Next("tz"))
          case _                                            => Left(WizardCopy.confirmActionHint)
        },
        onEnter = (data, accountId, tx) => confirmEnter(clock, data, accountId, tx)
      )
    ),
    onComplete = (data, ctx) => complete(clock, lifecycle, data, ctx)
  )

  /** The confirm step's database reads: the timezone default (the account's zone) and the find-or-create offer (R18).
    */
  private def confirmEnter(
      clock: Clock,
      data: Map[String, String],
      accountId: Option[UUID],
      tx: Tx
  ): Map[String, String] =
    val zone = data.getOrElse(TimezoneKey, accountId.flatMap(tx.accounts.timezoneOf).getOrElse("UTC"))
    val offer = accountId
      .flatMap(acc => tx.medications.findByNameNorm(acc, normalizeName(data.getOrElse(NameKey, ""))))
      .map(medication => Map(ExistingKey -> medication.id.toString))
      .getOrElse(Map.empty)
    val now = clock.now()
    val zoneId = ZoneId.of(zone)
    val rule = presetOf(data) match
      case Some(preset) => presetRule(preset, accountId, tx, now.atZone(zoneId))
      case None         => timesRule(data)
    val preview = MedicationPresets
      .preview(rule, zoneId, now, PreviewCount)
      .map(at => MedicationDepthCopy.date(at.atZone(zoneId).toLocalDate) + " " + formatClock(at, zoneId))
    Map(TimezoneKey -> zone, RuleKey -> write(rule), PreviewKey -> preview.mkString("|")) ++ offer

  /** The preset's rule on the user's day profile; a weekly preset falls on today's weekday, a cycle starts today. */
  private def presetRule(preset: MedicationPreset, accountId: Option[UUID], tx: Tx, now: ZonedDateTime): Rule =
    val profile = accountId
      .flatMap(tx.reminders.dayProfiles.anchors)
      .getOrElse(DayAnchors.of(DayProfile.Default))
      .withQuiet(DayProfile.Default.quiet)
    val params = MedicationPresetParams(weekday = Weekday.fromOrdinal(now.getDayOfWeek.getValue - 1))
    MedicationPresets.rule(preset, profile, params, now.toLocalDate)

  private def timesRule(data: Map[String, String]): Rule =
    val times = parseTimes(data.getOrElse(TimesKey, ""))
    if times.isEmpty then Rule.AsNeeded(maxPerDay = 10, minGapMinutes = 0)
    else Rule.FixedTimes(List(SlotGroup(daysOf(data), times)))

  private def formatClock(at: Instant, zone: ZoneId): String =
    val local = at.atZone(zone).toLocalTime
    f"${local.getHour}%02d:${local.getMinute}%02d"

  private def confirmOptions(data: Map[String, String]): List[(String, String)] =
    val existing = data.get(ExistingKey).exists(_.nonEmpty)
    val primary = if existing then List(UpdateKey -> Labels.Update) else List(CreateKey -> Labels.Create)
    val changeDays =
      if data.getOrElse(TimesKey, "").nonEmpty && presetOf(data).isEmpty then
        List(ChangeDaysKey -> WizardCopy.changeDays)
      else Nil
    primary ++ changeDays ++ List(ChangeTimezoneKey -> WizardCopy.changeTimezoneLabel)

  private def confirmRender(data: Map[String, String]): RichText =
    val name = data.getOrElse(NameKey, "")
    val dose = data.get(DoseKey).filter(_.nonEmpty)
    val zone = data.getOrElse(TimezoneKey, "UTC")
    val offer = data.get(ExistingKey).filter(_.nonEmpty).map(_ => WizardCopy.offerUpdate(name)).toList
    val preview =
      data.get(PreviewKey).filter(_.nonEmpty).map(p => MedicationDepthCopy.nextReminders(p.split('|').toList))
    val presetRule = presetOf(data).flatMap(_ => data.get(RuleKey).filter(_.nonEmpty)).map(read[Rule](_))
    presetRule match
      case Some(rule) =>
        (offer ++ List(MedicationDepthCopy.confirmCard(name, dose, RecordSummaries.ruleInWords(rule), zone)) ++
          preview.toList).map(paragraph)
      case None => timesCard(data, offer, preview)

  private def timesCard(data: Map[String, String], offer: List[String], preview: Option[String]): RichText =
    val name = data.getOrElse(NameKey, "")
    val dose = data.get(DoseKey).filter(_.nonEmpty)
    val times = data.getOrElse(TimesKey, "")
    val zone = data.getOrElse(TimezoneKey, "UTC")
    if times.isEmpty then
      (offer ++ WizardCopy.confirmCardAsNeeded(name, dose, zone) ++ List(WizardCopy.asNeededNote)).map(paragraph)
    else
      val days = daysOf(data)
      val cadence =
        if days == allDays then WizardCopy.cadenceEveryDay
        else if days == weekDays then WizardCopy.cadenceWeekdays
        else WizardCopy.cadenceDays(days.map(day => WizardCopy.weekdayLabels(day.ordinal)))
      (offer ++ WizardCopy.confirmCard(name, dose, cadence, times, zone) ++ preview.toList).map(paragraph)

  private def complete(
      clock: Clock,
      lifecycle: ScheduleLifecycle,
      data: Map[String, String],
      ctx: FlowContext
  ): Reply =
    ctx.principal.accountId match
      case None =>
        Reply(followUps =
          List(outbound(IdentityCopy.createFirst, s"medication.add:unlinked:${ctx.event.eventId.uuid}", ctx))
        )
      // The wizard is opened behind the consent; one that was open when the consent was withdrawn saves nothing.
      case Some(account) if !MedicationConsentGate.isOpen(ctx.tx, account.uuid) =>
        Reply(followUps =
          List(outbound(ConsentCopy.medicationOff, s"medication.add:off:${ctx.event.eventId.uuid}", ctx))
        )
      case Some(account) =>
        val now = clock.now()
        val zone = ZoneId.of(data.getOrElse(TimezoneKey, "UTC"))
        val presetRule = presetOf(data).flatMap(_ => data.get(RuleKey).filter(_.nonEmpty)).map(read[Rule](_))
        val rule = presetRule.getOrElse(timesRule(data))
        val times = ruleTimes(rule)
        val (amount, unit) = parseDose(data.getOrElse(DoseKey, ""))
        val instructions = data.get(InstructionsKey).map(_.trim).filter(_.nonEmpty)
        val name = data(NameKey)
        data.get(ExistingKey).filter(_.nonEmpty) match
          case None =>
            lifecycle.createInTx(
              ctx.tx,
              CreateSchedule(
                account.uuid,
                name,
                rule,
                zone,
                amount,
                unit,
                instructions,
                ReminderPolicy.Default,
                tzFollowsUser = true
              ),
              now
            )
            Reply(followUps = List(createdMessage(name, times, ctx)))
          case Some(existingId) =>
            // Find-or-create update (R18): the schedule is replaced through a new revision; the medication's dose
            // profile stays as recorded (the editable profile is M3.2).
            val schedule = ctx.tx.schedules
              .listForMedication(UUID.fromString(existingId))
              .headOption
              .getOrElse(throw new NoSuchElementException(s"medication $existingId has no schedule"))
            if schedule.status == ScheduleStatus.Paused then lifecycle.resumeInTx(ctx.tx, schedule.id, now)
            lifecycle.editInTx(ctx.tx, schedule.id, rule, None, None, now)
            Reply(followUps = List(createdMessage(name, times, ctx)))

  private def ruleTimes(rule: Rule): List[HhMm] = rule match
    case Rule.FixedTimes(groups)      => groups.flatMap(_.times).distinct
    case Rule.EveryNDays(_, times, _) => times
    case Rule.Cycle(_, _, times, _)   => times
    case _                            => Nil

  private def createdMessage(name: String, times: List[HhMm], ctx: FlowContext): OutboundMessage =
    val lines =
      List(WizardCopy.medicationCreated(name)) ++
        (if times.isEmpty then List(WizardCopy.asNeededNote) else Nil)
    OutboundMessage(
      body = lines.map(paragraph),
      dedupeKey = s"medication.add:done:${ctx.event.eventId.uuid}",
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )

  private def outbound(text: String, dedupeKey: String, ctx: FlowContext): OutboundMessage =
    OutboundMessage(
      body = List(paragraph(text)),
      dedupeKey = dedupeKey,
      correlationId = s"${ctx.event.vendor}:${ctx.event.vendorEventId}"
    )

  private def paragraph(text: String): Node = Node.Paragraph(List(Inline.Text(text)))
end AddMedicationFlow
