package dosecord.core.domain.copy

/** Add-medication wizard and conversation copy (ROADMAP M1.4a; flow per docs/MEDICATION_REMINDER_UX.md and ROADMAP
  * M1.9). The wizard's step 1 form fields are pinned by the suite B1 form golden.
  */
object WizardCopy:

  val addMedicationIntro = "Let's add a medication."
  val addMedicationTitle = "Add medication"

  val fieldName = "Name"
  val fieldDose = "Dose"
  val fieldTimes = "Times (HH:MM, comma-separated)"
  val fieldInstructions = "Instructions"
  val namePlaceholder = "e.g. Vitamin D"
  val dosePlaceholder = "e.g. 1000 IU"
  val timesPlaceholder = "e.g. 09:00 or 09:00, 21:00 — empty for as-needed"
  val instructionsPlaceholder = "e.g. with breakfast"

  /** Step 1 validation failures (re-shown with the form). */
  val nameRequired = "Please give a name."
  val invalidTimes = "Times must be HH:MM, comma-separated — or leave empty for as-needed."
  val invalidDays = "Pick days by number (1-7) or name, like 1 3 5 or Mon, Wed."

  /** Step 2 days picker: [Every day][Weekdays][Choose days...] with the multi-select day picker behind the last. */
  val scheduleSetupIntro = "Set up your schedule."
  val daysPrompt = "Which days?"
  val everyDay = "Every day"
  val weekdays = "Weekdays"
  val chooseDays = "Choose days..."
  val weekdayLabels: List[String] = List("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

  /** As-needed branch (UX spec): no automatic reminders, manual logging only. */
  val asNeededNote =
    "I will not remind you automatically. You can log it from Today's doses or with /log."

  /** Step 3 confirm card:
    * `Vitamin D 1000 IU, every day 09:00 Europe/Kyiv [Create][Change days][Change timezone][Cancel]`.
    */
  def confirmCard(name: String, dose: Option[String], cadence: String, times: String, zone: String): List[String] =
    val title = dose.fold(name)(d => s"$name $d")
    List(s"$title, $cadence $times $zone")

  /** The as-needed variant of the confirm card (no days, no times). */
  def confirmCardAsNeeded(name: String, dose: Option[String], zone: String): List[String] =
    val title = dose.fold(name)(d => s"$name $d")
    List(s"$title, as needed $zone")

  /** Cadence phrases of the confirm card. */
  val cadenceEveryDay = "every day"
  val cadenceWeekdays = "on weekdays"
  def cadenceDays(days: List[String]): String = s"on ${days.mkString(", ")}"

  /** Confirm-card controls beyond the primary action. */
  val changeDays = "Change days"
  val changeTimezoneLabel = "Change timezone"

  /** The find-or-create offer (R18): re-adding a tracked name offers the update path instead of a duplicate. */
  def offerUpdate(name: String): String = s"You already track $name — Update replaces its schedule."

  /** Completion line after `[Create]`/`[Update]`. */
  def medicationCreated(name: String): String = s"$name is set."

  /** Re-prompt when a confirm-card tap matches no card action (only stale or forged slots reach it). */
  val confirmActionHint = "Tap Create to finish, or Cancel."

  /** Reminder policy step (M1.9): the ADR-012 defaults, exposed under Medications -> Settings. */
  val reminderPolicyPrompt = "Reminder options?"
  val policyAtDoseTime = "At dose time"
  val policyFiveMinutesBefore = "5 min before"
  val policyTenMinutesAfter = "10 min after if unanswered"

  /** Timezone echo on create and on Account -> Timezone (R10). */
  def timezoneEcho(time: String): String = s"It is $time for you now."
  def timezoneConfirm(time: String): String = s"It is $time for you now, right?"

  /** Session sweeper (M0.12b): one grace prompt at 30 min idle, abort after a further 30 min. */
  val stillThere = "Still there?"
  val setupCancelled = "Setup cancelled — nothing saved."
  val cancelCurrentSetup = "Cancel the current setup?"
  val nothingToCancel = "There is no setup in progress."

  /** Text/form steps carry Back/Cancel as a typed hint instead of numbered controls, so a bare digit is never eaten
    * while the wizard waits for free text (DESIGN.md section 4.6 step 5).
    */
  val textNavHint = "Reply Back to go back or Cancel to stop."

  /** FormRunner: a blank answer to a required field (M0.12b). */
  val fieldRequired = "That one is required — please type an answer."

  val entries: List[CopyEntry] = List(
    CopyEntry("wizard.add.intro", addMedicationIntro),
    CopyEntry("wizard.add.title", addMedicationTitle),
    CopyEntry("wizard.add.field.name", fieldName),
    CopyEntry("wizard.add.field.dose", fieldDose),
    CopyEntry("wizard.add.field.times", fieldTimes),
    CopyEntry("wizard.add.field.instructions", fieldInstructions),
    CopyEntry("wizard.add.placeholder.name", namePlaceholder),
    CopyEntry("wizard.add.placeholder.dose", dosePlaceholder),
    CopyEntry("wizard.add.placeholder.times", timesPlaceholder),
    CopyEntry("wizard.add.placeholder.instructions", instructionsPlaceholder),
    CopyEntry("wizard.add.name_required", nameRequired),
    CopyEntry("wizard.add.invalid_times", invalidTimes),
    CopyEntry("wizard.add.invalid_days", invalidDays),
    CopyEntry("wizard.days.intro", scheduleSetupIntro),
    CopyEntry("wizard.days.prompt", daysPrompt),
    CopyEntry("wizard.days.every_day", everyDay),
    CopyEntry("wizard.days.weekdays", weekdays),
    CopyEntry("wizard.days.choose", chooseDays),
    CopyEntry("wizard.as_needed.note", asNeededNote),
    CopyEntry(
      "wizard.confirm.card",
      confirmCard("Vitamin D", Some("1000 IU"), "every day", "09:00", "Europe/Kyiv").mkString("\n")
    ),
    CopyEntry(
      "wizard.confirm.card.as_needed",
      confirmCardAsNeeded("Vitamin D", Some("1000 IU"), "Europe/Kyiv").mkString("\n")
    ),
    CopyEntry("wizard.confirm.cadence.every_day", cadenceEveryDay),
    CopyEntry("wizard.confirm.cadence.weekdays", cadenceWeekdays),
    CopyEntry("wizard.confirm.cadence.days", cadenceDays(List("Mon", "Wed", "Fri"))),
    CopyEntry("wizard.confirm.change_days", changeDays),
    CopyEntry("wizard.confirm.change_timezone", changeTimezoneLabel),
    CopyEntry("wizard.confirm.offer_update", offerUpdate("Vitamin D")),
    CopyEntry("wizard.confirm.created", medicationCreated("Vitamin D")),
    CopyEntry("wizard.confirm.action_hint", confirmActionHint),
    CopyEntry("wizard.policy.prompt", reminderPolicyPrompt),
    CopyEntry("wizard.policy.at_dose_time", policyAtDoseTime),
    CopyEntry("wizard.policy.five_before", policyFiveMinutesBefore),
    CopyEntry("wizard.policy.ten_after", policyTenMinutesAfter),
    CopyEntry("wizard.timezone.echo", timezoneEcho("14:32")),
    CopyEntry("wizard.timezone.confirm", timezoneConfirm("14:32")),
    CopyEntry("wizard.still_there", stillThere),
    CopyEntry("wizard.setup_cancelled", setupCancelled),
    CopyEntry("wizard.cancel_current_setup", cancelCurrentSetup),
    CopyEntry("wizard.nothing_to_cancel", nothingToCancel),
    CopyEntry("wizard.text_nav_hint", textNavHint),
    CopyEntry("wizard.field_required", fieldRequired)
  )
end WizardCopy
