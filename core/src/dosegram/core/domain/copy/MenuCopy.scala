package dosegram.core.domain.copy

/** Menu copy (ROADMAP M1.4a recorded decision; tree per docs/MEDICATION_REMINDER_UX.md and ROADMAP M1.9). */
object MenuCopy:

  def mainPrompt = tr("What would you like to do?", "Що бажаєте зробити?")

  /** Recorded decision: the top menu is exactly these six entries, in this order. */
  def topLevel: List[String] = List(
    tr("Today", "Сьогодні"),
    tr("Medications", "Ліки"),
    tr("Habits", "Звички"),
    tr("Reminders", "Нагадування"),
    tr("Stats", "Статистика"),
    tr("Account", "Акаунт")
  )

  /** Medications submenu (M1.9). Unshipped entries are hidden by flag, never shown as "under development". */
  def medicationsTodaysDoses = tr("Today's doses", "Дози на сьогодні")
  def medicationsAdd = tr("Add medication", "Додати ліки")
  def medicationsLogDose = tr("Log dose", "Записати дозу")
  def medicationsPause = tr("Pause", "Призупинити")
  def medicationsResume = tr("Resume", "Відновити")
  def medicationsSettings = tr("Settings", "Налаштування")
  def medicationsHistory = tr("History", "Історія")

  /** Submenu prompts and empty states. */
  def medicationsPrompt = tr("Medications", "Ліки")
  def accountPrompt = tr("Account", "Акаунт")
  def menuLabel = tr("Menu", "Меню")
  def medicationsEmpty = tr("No medications yet.", "Ліків поки немає.")

  /** `/app` (BOT_UX section 6.1): one card whose button opens the Mini App. */
  def appPrompt = tr("Your records and settings are in the app.", "Ваші записи й налаштування — у застосунку.")
  def openApp = tr("Open app", "Відкрити застосунок")
  def todayEmpty = tr("Nothing scheduled for today.", "На сьогодні нічого не заплановано.")
  def todayMore(count: Int): String = tr(s"+$count more — see /history.", s"І ще $count — дивіться /history.")
  def historyTitle = tr("Last 7 days", "Останні 7 днів")
  def historyEmpty = tr("No doses in the last 7 days.", "За останні 7 днів доз немає.")
  def settingsTitle = tr("Settings", "Налаштування")
  def settingsIntro = tr("Reminder options for each medication:", "Параметри нагадувань для кожних ліків:")

  /** Pause/Resume toggle line (M1.9): `Paused since Tue [Resume]`. */
  def pausedSince(day: String): String = tr(s"Paused since $day", s"Призупинено з $day")

  /** Per-medication lifecycle outcomes (the toggle re-renders the submenu). */
  def pausedConfirm(name: String): String = tr(
    s"Paused $name — no reminders until you resume.",
    s"$name призупинено — нагадувань не буде, доки ви не відновите."
  )
  def resumedConfirm(name: String, next: String): String =
    tr(s"Resumed $name — next dose $next.", s"$name відновлено — наступна доза $next.")
  def resumedConfirmNoNext(name: String): String = tr(s"Resumed $name.", s"$name відновлено.")
  def archiveConfirmPrompt(name: String): String = tr(
    s"Archive $name? Its schedule stops; history is kept.",
    s"Перенести $name в архів? Розклад зупиниться, історія збережеться."
  )
  def archivedConfirm(name: String): String = tr(s"Archived $name.", s"$name перенесено в архів.")

  /** The reminder-policy line of Medications -> Settings (ADR-012 defaults, read-mostly). */
  def settingsPolicyLine(name: String, offset: String, snoozes: String, missAfterMinutes: Int): String =
    tr(
      s"$name — reminders $offset; snooze $snoozes; marked missed after $missAfterMinutes min without a response.",
      s"$name — нагадування $offset; відкласти на $snoozes; без відповіді позначається як пропущене через $missAfterMinutes хв."
    )
  def policyOffsetAtDoseTime = tr("at dose time", "у час прийому")
  def policyOffsetBefore(minutes: Int): String = tr(s"$minutes min before", s"за $minutes хв до прийому")
  def policyOffsetAfter(minutes: Int): String =
    tr(s"$minutes min after if unanswered", s"через $minutes хв, якщо немає відповіді")
  def snoozeList(minutes: List[Int]): String = minutes.map(m => tr(s"${m}m", s"$m хв")).mkString(", ")

  /** `/today` row status phrases. */
  def statusScheduled = tr("scheduled", "заплановано")
  def statusDue = tr("due now", "час прийому")
  def statusSnoozedUntil(time: String): String = tr(s"snoozed until $time", s"відкладено до $time")
  def statusTakenAt(time: String): String = tr(s"taken at $time", s"прийнято о $time")
  def statusSkippedAt(time: String): String = tr(s"skipped at $time", s"пропущено о $time")
  def statusMissed = tr("missed", "не підтверджено")
  def statusUnknown = tr("unknown", "невідомо")
  def takenLateSuffix = tr("late", "із запізненням")

  /** A Today row whose dose has no snapshot. */
  def yourDose: String = tr("Your dose", "Ваша доза")
  def asNeededRow(name: String): String = tr(s"$name — as needed", s"$name — за потреби")
  def logDoseLabel(name: String): String = tr(s"Log $name", s"Записати: $name")

  def entries: List[CopyEntry] =
    (CopyEntry("menu.main.prompt", mainPrompt) +:
      List("today", "medications", "habits", "reminders", "stats", "account")
        .zip(topLevel)
        .map((key, label) => CopyEntry(s"menu.main.$key", label))) ++
      List(
        CopyEntry("menu.medications.todays_doses", medicationsTodaysDoses),
        CopyEntry("menu.medications.add", medicationsAdd),
        CopyEntry("menu.medications.log_dose", medicationsLogDose),
        CopyEntry("menu.medications.pause", medicationsPause),
        CopyEntry("menu.medications.resume", medicationsResume),
        CopyEntry("menu.medications.settings", medicationsSettings),
        CopyEntry("menu.medications.history", medicationsHistory),
        CopyEntry("menu.medications.paused_since", pausedSince("Tue")),
        CopyEntry("menu.medications.prompt", medicationsPrompt),
        CopyEntry("menu.account.prompt", accountPrompt),
        CopyEntry("menu.app.prompt", appPrompt),
        CopyEntry("menu.app.open", openApp),
        CopyEntry("menu.nav.label", menuLabel),
        CopyEntry("menu.medications.empty", medicationsEmpty),
        CopyEntry("menu.today.empty", todayEmpty),
        CopyEntry("menu.today.more", todayMore(3)),
        CopyEntry("menu.history.title", historyTitle),
        CopyEntry("menu.history.empty", historyEmpty),
        CopyEntry("menu.settings.title", settingsTitle),
        CopyEntry("menu.settings.intro", settingsIntro),
        CopyEntry(
          "menu.settings.policy_line",
          settingsPolicyLine("Vitamin D", policyOffsetAtDoseTime, snoozeList(List(10, 30, 60)), 120)
        ),
        CopyEntry("menu.settings.offset_at_dose_time", policyOffsetAtDoseTime),
        CopyEntry("menu.settings.offset_before", policyOffsetBefore(5)),
        CopyEntry("menu.settings.offset_after", policyOffsetAfter(10)),
        CopyEntry("menu.settings.snooze_list", snoozeList(List(10, 30, 60))),
        CopyEntry("menu.status.scheduled", statusScheduled),
        CopyEntry("menu.status.due", statusDue),
        CopyEntry("menu.status.snoozed", statusSnoozedUntil("09:10")),
        CopyEntry("menu.status.taken", statusTakenAt("09:03")),
        CopyEntry("menu.status.skipped", statusSkippedAt("12:00")),
        CopyEntry("menu.status.missed", statusMissed),
        CopyEntry("menu.status.unknown", statusUnknown),
        CopyEntry("menu.status.taken_late", takenLateSuffix),
        CopyEntry("menu.today.your_dose", yourDose),
        CopyEntry("menu.today.as_needed_row", asNeededRow("Vitamin C")),
        CopyEntry("menu.today.log_dose", logDoseLabel("Vitamin C")),
        CopyEntry("menu.medications.paused_confirm", pausedConfirm("Vitamin D")),
        CopyEntry("menu.medications.resumed_confirm", resumedConfirm("Vitamin D", "Tue 09:00")),
        CopyEntry("menu.medications.resumed_no_next", resumedConfirmNoNext("Vitamin D")),
        CopyEntry("menu.medications.archive_prompt", archiveConfirmPrompt("Vitamin D")),
        CopyEntry("menu.medications.archived_confirm", archivedConfirm("Vitamin D"))
      )
end MenuCopy
