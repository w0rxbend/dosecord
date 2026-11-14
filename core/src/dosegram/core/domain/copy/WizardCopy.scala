package dosegram.core.domain.copy

/** Add-medication wizard and conversation copy (ROADMAP M1.4a; flow per docs/MEDICATION_REMINDER_UX.md and ROADMAP
  * M1.9). The wizard's step 1 form fields are pinned by the suite B1 form golden.
  */
object WizardCopy:

  def addMedicationIntro = tr("Let's add a medication.", "Додаймо ліки.")
  def addMedicationTitle = tr("Add medication", "Додати ліки")

  def fieldName = tr("Name", "Назва")
  def fieldDose = tr("Dose", "Доза")
  def namePlaceholder = tr("e.g. Vitamin D", "напр. Vitamin D")
  def dosePlaceholder = tr("e.g. 1000 IU", "напр. 1000 IU")

  /** The times step after the form (BOT_UX section 10.1, section 15.6): quick times, Other time, As needed. */
  def timesPrompt = tr(
    "When should I remind you? Tap a time, or type one or more, like 9, 14:30 or 9pm.",
    "Коли нагадувати? Натисніть на час або введіть один чи кілька, як-от 9, 14:30 чи 21:00."
  )
  def quickTimes: List[String] = List("08:00", "09:00", "13:00", "20:00", "22:00")
  def otherTime = tr("Other time", "Інший час")
  def asNeeded = tr("As needed", "За потреби")
  def timesOtherPrompt = tr(
    "Type the time or times, like 7:30, 9pm or 8, 14:30 and 22.",
    "Введіть час або кілька, як-от 7:30, 21:00 чи 8, 14:30 і 22."
  )

  /** The echo of what the times step understood, shown above the instructions question with its Change path. */
  def timesEcho(times: List[String]): String =
    times match
      case Nil =>
        tr(
          "No reminder times: you log it yourself when you take it.",
          "Без часу нагадувань: ви записуєте прийом самі, коли приймаєте."
        )
      case List(first) => tr(s"Reminder time: $first.", s"Час нагадування: $first.")
      case several     =>
        tr(
          s"Reminder times: ${several.init.mkString(", ")} and ${several.last}.",
          s"Час нагадувань: ${several.init.mkString(", ")} і ${several.last}."
        )

  def instructionsPrompt = tr(
    "Any instructions to show with the reminder? Type them, like with breakfast.",
    "Додати інструкцію до нагадування? Введіть її, як-от «під час сніданку»."
  )
  def noInstructions = tr("No instructions", "Без інструкцій")
  def changeTimes = tr("Change times", "Змінити час")

  /** Step 1 validation failures (re-shown with the form). */
  def nameRequired = tr("Please give a name.", "Вкажіть, будь ласка, назву.")
  def invalidDays = tr(
    "Pick days by number (1-7) or name, like 1 3 5 or Mon, Wed.",
    "Виберіть дні за номером (1–7) або назвою, як-от 1 3 5 чи пн, ср."
  )

  /** Step 2 days picker: [Every day][Weekdays][Choose days...] with the multi-select day picker behind the last. */
  def scheduleSetupIntro = tr("Set up your schedule.", "Налаштуймо розклад.")
  def daysPrompt = tr("Which days?", "Які дні?")
  def everyDay = tr("Every day", "Щодня")
  def weekdays = tr("Weekdays", "Будні")
  def chooseDays = tr("Choose days...", "Вибрати дні…")
  def weekdayLabels: List[String] =
    tr(List("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), List("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд"))

  /** As-needed branch (UX spec): no automatic reminders, manual logging only. */
  def asNeededNote =
    tr(
      "I will not remind you automatically. You can log it from Today's doses or with /log.",
      "Автоматичних нагадувань не буде. Записати прийом можна в «Дози на сьогодні» або командою /log."
    )

  /** Step 3 confirm card:
    * `Vitamin D 1000 IU, every day 09:00 Europe/Kyiv [Create][Change days][Change timezone][Cancel]`.
    */
  def confirmCard(name: String, dose: Option[String], cadence: String, times: String, zone: String): List[String] =
    val title = dose.fold(name)(d => s"$name $d")
    List(s"$title, $cadence $times $zone")

  /** The as-needed variant of the confirm card (no days, no times). */
  def confirmCardAsNeeded(name: String, dose: Option[String], zone: String): List[String] =
    val title = dose.fold(name)(d => s"$name $d")
    List(tr(s"$title, as needed $zone", s"$title, за потреби, $zone"))

  /** Cadence phrases of the confirm card. */
  def cadenceEveryDay = tr("every day", "щодня")
  def cadenceWeekdays = tr("on weekdays", "у будні")
  def cadenceDays(days: List[String]): String = tr(s"on ${days.mkString(", ")}", s"у дні: ${days.mkString(", ")}")

  /** Confirm-card controls beyond the primary action. */
  def changeDays = tr("Change days", "Змінити дні")
  def changeTimezoneLabel = tr("Change timezone", "Змінити пояс")

  /** The find-or-create offer (R18): re-adding a tracked name offers the update path instead of a duplicate. */
  def offerUpdate(name: String): String = tr(
    s"You already track $name — Update replaces its schedule.",
    s"Ви вже ведете облік $name — «Оновити» замінить його розклад."
  )

  /** Completion line after `[Create]`/`[Update]`. */
  def medicationCreated(name: String): String = tr(s"$name is set.", s"$name налаштовано.")

  /** Re-prompt when a confirm-card tap matches no card action (only stale or forged slots reach it). */
  def confirmActionHint =
    tr("Tap Create to finish, or Cancel.", "Натисніть «Створити», щоб завершити, або «Скасувати».")

  /** Reminder policy step (M1.9): the ADR-012 defaults, exposed under Medications -> Settings. */
  def reminderPolicyPrompt = tr("Reminder options?", "Параметри нагадувань?")
  def policyAtDoseTime = tr("At dose time", "У час прийому")
  def policyFiveMinutesBefore = tr("5 min before", "За 5 хв до прийому")
  def policyTenMinutesAfter = tr("10 min after if unanswered", "Через 10 хв, якщо немає відповіді")

  /** Timezone echo on create and on Account -> Timezone (R10). */
  def timezoneEcho(time: String): String = tr(s"It is $time for you now.", s"У вас зараз $time.")
  def timezoneConfirm(time: String, zone: String): String =
    tr(s"It is $time in $zone now. Correct?", s"Зараз у $zone $time. Правильно?")

  /** Session sweeper (M0.12b): one grace prompt at 30 min idle, abort after a further 30 min. */
  def stillThere = tr("Still there?", "Ви ще тут?")
  def setupCancelled = tr("Setup cancelled — nothing saved.", "Налаштування скасовано — нічого не збережено.")
  def cancelCurrentSetup = tr("Cancel the current setup?", "Скасувати поточне налаштування?")
  def nothingToCancel = tr("There is no setup in progress.", "Зараз нічого не налаштовується.")

  /** Text/form steps carry Back/Cancel as a typed hint instead of numbered controls, so a bare digit is never eaten
    * while the wizard waits for free text (DESIGN.md section 4.6 step 5).
    */
  def textNavHint = tr(
    "Reply Back to go back or Cancel to stop.",
    "Напишіть «Назад», щоб повернутися, або «Скасувати», щоб зупинитися."
  )

  /** FormRunner: a blank answer to a required field (M0.12b). */
  def fieldRequired =
    tr("That one is required — please type an answer.", "Це поле обов’язкове — введіть, будь ласка, відповідь.")

  def entries: List[CopyEntry] = List(
    CopyEntry("wizard.add.intro", addMedicationIntro),
    CopyEntry("wizard.add.title", addMedicationTitle),
    CopyEntry("wizard.add.field.name", fieldName),
    CopyEntry("wizard.add.field.dose", fieldDose),
    CopyEntry("wizard.add.placeholder.name", namePlaceholder),
    CopyEntry("wizard.add.placeholder.dose", dosePlaceholder),
    CopyEntry("wizard.add.name_required", nameRequired),
    CopyEntry("wizard.times.prompt", timesPrompt),
    CopyEntry("wizard.times.other", otherTime),
    CopyEntry("wizard.times.as_needed", asNeeded),
    CopyEntry("wizard.times.other_prompt", timesOtherPrompt),
    CopyEntry("wizard.times.echo.none", timesEcho(Nil)),
    CopyEntry("wizard.times.echo.one", timesEcho(List("09:00"))),
    CopyEntry("wizard.times.echo.several", timesEcho(List("08:00", "14:30", "22:00"))),
    CopyEntry("wizard.instructions.prompt", instructionsPrompt),
    CopyEntry("wizard.instructions.none", noInstructions),
    CopyEntry("wizard.times.change", changeTimes),
    CopyEntry("wizard.add.invalid_days", invalidDays),
    CopyEntry("wizard.days.intro", scheduleSetupIntro),
    CopyEntry("wizard.days.prompt", daysPrompt),
    CopyEntry("wizard.days.every_day", everyDay),
    CopyEntry("wizard.days.weekdays", weekdays),
    CopyEntry("wizard.days.choose", chooseDays),
    CopyEntry("wizard.as_needed.note", asNeededNote),
    CopyEntry(
      "wizard.confirm.card",
      confirmCard("Vitamin D", Some("1000 IU"), tr("every day", "щодня"), "09:00", "Europe/Kyiv").mkString("\n")
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
    CopyEntry("wizard.timezone.confirm", timezoneConfirm("17:42", "Europe/Kyiv")),
    CopyEntry("wizard.still_there", stillThere),
    CopyEntry("wizard.setup_cancelled", setupCancelled),
    CopyEntry("wizard.cancel_current_setup", cancelCurrentSetup),
    CopyEntry("wizard.nothing_to_cancel", nothingToCancel),
    CopyEntry("wizard.text_nav_hint", textNavHint),
    CopyEntry("wizard.field_required", fieldRequired)
  )
end WizardCopy
