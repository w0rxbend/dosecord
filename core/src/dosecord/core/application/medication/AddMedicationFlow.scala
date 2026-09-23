package dosecord.core.application.medication

import dosecord.contracts.*
import dosecord.core.application.identity.AccountCreateFlow
import dosecord.core.chat.Flow
import dosecord.core.chat.FlowContext
import dosecord.core.chat.Step
import dosecord.core.chat.StepInput
import dosecord.core.chat.StepKind
import dosecord.core.chat.StepTransition
import dosecord.core.domain.ReminderPolicy
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup
import dosecord.core.domain.copy.IdentityCopy
import dosecord.core.domain.copy.Labels
import dosecord.core.domain.copy.WizardCopy
import dosecord.core.ports.Clock
import dosecord.core.ports.ScheduleStatus
import dosecord.core.ports.Tx
import dosecord.core.scheduling.CreateSchedule
import dosecord.core.scheduling.ScheduleLifecycle

import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import scala.util.matching.Regex

/** The add-medication wizard (ROADMAP M1.9, R11/R13/R17/R18; flow per docs/MEDICATION_REMINDER_UX.md): a declarative
  * `Flow` over the M0.12b engine.
  *
  *   - step 1 `Form(name, dose, times, instructions)` — one modal on vendors with modal capability, four FormRunner
  *     questions on the text tiers; times is an `HH:MM` list, empty = as-needed; instructions optional.
  *   - step 2 (only when times were given) days `[Every day][Weekdays][Choose days...]`, the multi-select day picker
  *     behind the last.
  *   - step 3 one confirm card `Vitamin D 1000 IU, every day 09:00 Europe/Kyiv [Create][Change days][Change timezone]
  *     [Cancel]` (as-needed cards skip Change days).
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

  // Step choice keys.
  private val EveryDayKey = "every_day"
  private val WeekdaysKey = "weekdays"
  private val ChooseDaysKey = "choose_days"
  private val CreateKey = "create"
  private val UpdateKey = "update"
  private val ChangeDaysKey = "change_days"
  private val ChangeTimezoneKey = "change_tz"
  private val CancelKey = "cancel"
  private val TzOtherKey = "tz.other"

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
    ),
    Field(
      TimesKey,
      WizardCopy.fieldTimes,
      FieldType.Text,
      required = false,
      placeholder = Some(WizardCopy.timesPlaceholder)
    ),
    Field(
      InstructionsKey,
      WizardCopy.fieldInstructions,
      FieldType.Text,
      required = false,
      placeholder = Some(WizardCopy.instructionsPlaceholder)
    )
  )

  /** `HH:MM` comma-separated list; empty means as-needed (the wizard's as-needed branch). */
  def parseTimes(text: String): Either[String, List[HhMm]] =
    val trimmed = text.trim
    if trimmed.isEmpty then Right(Nil)
    else
      val parts = trimmed.split(",").toList.map(_.trim)
      val parsed = parts.map(HhMm.parse)
      parsed.collectFirst { case Left(_) => () } match
        case Some(_) => Left(WizardCopy.invalidTimes)
        case None    => Right(parsed.collect { case Right(t) => t }.distinct)

  private val DosePattern: Regex = "^([0-9]+(?:[.,][0-9]+)?)\\s*(.*)$".r

  /** "1000 IU" -> (Some(1000), Some("IU")); a leading number is the amount, the rest the unit; anything else is kept as
    * the unit text alone.
    */
  def parseDose(text: String): (Option[BigDecimal], Option[String]) =
    text.trim match
      case ""                        => (None, None)
      case DosePattern(number, rest) =>
        val unit = rest.trim
        (Some(BigDecimal(number.replace(',', '.'))), if unit.isEmpty then None else Some(unit))
      case other => (None, Some(other))

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

  /** "1 3 5", "1,3,5" or "Mon, Wed" on the text tiers; None when any token is not a day. */
  def parseDaySelection(text: String): Option[List[String]] =
    val tokens = text.trim.split("[\\s,]+").toList.filter(_.nonEmpty)
    if tokens.isEmpty then None
    else
      val days = tokens.map: token =>
        token.toIntOption
          .filter(i => i >= 1 && i <= 7)
          .map(i => allDays(i - 1))
          .orElse(
            allDays.find(_.toString.equalsIgnoreCase(token))
          )
      if days.exists(_.isEmpty) then None else Some(canonicalDays(days.flatten.map(_.toString)))

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
              parseTimes(fields.getOrElse(TimesKey, "")) match
                case Left(reason) => Left(reason)
                case Right(times) =>
                  Right(
                    StepTransition.Next(
                      if times.isEmpty then "confirm" else "days",
                      Map(
                        NameKey -> name,
                        DoseKey -> fields.getOrElse(DoseKey, "").trim,
                        TimesKey -> fields.getOrElse(TimesKey, "").trim,
                        InstructionsKey -> fields.getOrElse(InstructionsKey, "").trim,
                        // Cleared on every form submit; the confirm step's onEnter recomputes the offer.
                        ExistingKey -> ""
                      )
                    )
                  )
          case _ => Left(WizardCopy.nameRequired)
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
          ChoiceLayout.Select,
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
            parseDaySelection(text) match
              case Some(days) => Right(StepTransition.Next("confirm", Map(DaysKey -> days.mkString(","))))
              case None       => Left(WizardCopy.invalidDays)
          case _ => Left(WizardCopy.invalidDays)
        }
      ),
      "tz" -> Step(
        id = "tz",
        kind = StepKind.Choices(_ => AccountCreateFlow.pickerOptions(clock.now()), ChoiceLayout.Select),
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
            AccountCreateFlow.parseZone(text) match
              case Some(zone) =>
                Right(StepTransition.Next("confirm", Map(TimezoneKey -> zone.getId, TzErrorKey -> "")))
              case None => Right(StepTransition.Next("tz", Map(TzErrorKey -> IdentityCopy.invalidZone)))
          case _ => Left(IdentityCopy.invalidZone)
        }
      ),
      "confirm" -> Step(
        id = "confirm",
        kind = StepKind.Choices(confirmOptions, ChoiceLayout.Buttons),
        render = confirmRender,
        accept = {
          case (StepInput.Chosen(CreateKey | UpdateKey), _) => Right(StepTransition.Complete)
          case (StepInput.Chosen(ChangeDaysKey), _)         => Right(StepTransition.Next("days"))
          case (StepInput.Chosen(ChangeTimezoneKey), _)     => Right(StepTransition.Next("tz"))
          case (StepInput.Chosen(CancelKey), _)             => Right(StepTransition.Abort)
          case _                                            => Left(WizardCopy.confirmActionHint)
        },
        onEnter = confirmEnter
      )
    ),
    onComplete = (data, ctx) => complete(clock, lifecycle, data, ctx)
  )

  /** The confirm step's database reads: the timezone default (the account's zone) and the find-or-create offer (R18).
    */
  private def confirmEnter(data: Map[String, String], accountId: Option[UUID], tx: Tx): Map[String, String] =
    val zone = data.getOrElse(TimezoneKey, accountId.flatMap(tx.accounts.timezoneOf).getOrElse("UTC"))
    val offer = accountId
      .flatMap(acc => tx.medications.findByNameNorm(acc, normalizeName(data.getOrElse(NameKey, ""))))
      .map(medication => Map(ExistingKey -> medication.id.toString))
      .getOrElse(Map.empty)
    Map(TimezoneKey -> zone) ++ offer

  private def confirmOptions(data: Map[String, String]): List[(String, String)] =
    val existing = data.get(ExistingKey).exists(_.nonEmpty)
    val primary = if existing then List(UpdateKey -> Labels.Update) else List(CreateKey -> Labels.Create)
    val changeDays =
      if data.getOrElse(TimesKey, "").nonEmpty then List(ChangeDaysKey -> WizardCopy.changeDays) else Nil
    primary ++ changeDays ++
      List(ChangeTimezoneKey -> WizardCopy.changeTimezoneLabel, CancelKey -> Labels.Cancel)

  private def confirmRender(data: Map[String, String]): RichText =
    val name = data.getOrElse(NameKey, "")
    val dose = data.get(DoseKey).filter(_.nonEmpty)
    val times = data.getOrElse(TimesKey, "")
    val zone = data.getOrElse(TimezoneKey, "UTC")
    val offer = data.get(ExistingKey).filter(_.nonEmpty).map(_ => WizardCopy.offerUpdate(name)).toList
    if times.isEmpty then
      (offer ++ WizardCopy.confirmCardAsNeeded(name, dose, zone) ++ List(WizardCopy.asNeededNote)).map(paragraph)
    else
      val days = daysOf(data)
      val cadence =
        if days == allDays then WizardCopy.cadenceEveryDay
        else if days == weekDays then WizardCopy.cadenceWeekdays
        else WizardCopy.cadenceDays(days.map(_.toString))
      (offer ++ WizardCopy.confirmCard(name, dose, cadence, times, zone)).map(paragraph)

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
      case Some(account) =>
        val now = clock.now()
        val zone = ZoneId.of(data.getOrElse(TimezoneKey, "UTC"))
        val times = parseTimes(data.getOrElse(TimesKey, "")).getOrElse(Nil)
        val rule =
          if times.isEmpty then Rule.AsNeeded(maxPerDay = 10, minGapMinutes = 0)
          else Rule.FixedTimes(List(SlotGroup(daysOf(data), times)))
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
