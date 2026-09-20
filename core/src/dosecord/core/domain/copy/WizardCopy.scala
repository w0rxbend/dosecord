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

  val entries: List[CopyEntry] = List(
    CopyEntry("wizard.add.intro", addMedicationIntro),
    CopyEntry("wizard.add.title", addMedicationTitle),
    CopyEntry("wizard.add.field.name", fieldName),
    CopyEntry("wizard.add.field.dose", fieldDose),
    CopyEntry("wizard.add.field.times", fieldTimes),
    CopyEntry("wizard.add.field.instructions", fieldInstructions),
    CopyEntry("wizard.add.placeholder.name", namePlaceholder),
    CopyEntry("wizard.add.placeholder.dose", dosePlaceholder),
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
    CopyEntry("wizard.policy.prompt", reminderPolicyPrompt),
    CopyEntry("wizard.policy.at_dose_time", policyAtDoseTime),
    CopyEntry("wizard.policy.five_before", policyFiveMinutesBefore),
    CopyEntry("wizard.policy.ten_after", policyTenMinutesAfter),
    CopyEntry("wizard.timezone.echo", timezoneEcho("14:32")),
    CopyEntry("wizard.timezone.confirm", timezoneConfirm("14:32")),
    CopyEntry("wizard.still_there", stillThere),
    CopyEntry("wizard.setup_cancelled", setupCancelled),
    CopyEntry("wizard.cancel_current_setup", cancelCurrentSetup)
  )
end WizardCopy
