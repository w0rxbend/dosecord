package dosegram.core.domain.copy

import dosegram.core.domain.insights.DateRange

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale as JavaLocale

/** The copy of the export (BOT_UX sections 20.2 and 22: `privacy.export.*`) and of the record summary document
  * (VISUALS_AND_ANALYTICS DOC-01, PRODUCT_SPEC R-IMX-6). The record summary is never called a clinical or medical
  * report; it states what the user recorded and interprets nothing.
  */
object ExportCopy:

  /** The version of the warning below; the per-export consent `export_to_chat` records it. */
  val WarningVersion: Int = 1

  private def Day: DateTimeFormatter = Formats.day
  private def DayMonth: DateTimeFormatter = Formats.dayMonth
  private def Clock = DateTimeFormatter.ofPattern("HH:mm", JavaLocale.ROOT)

  // ---------- chat (BOT_UX section 20.2) ----------

  /** `privacy.export.confirm`. */
  def confirm = tr(
    "Export all your records? The file will contain everything you logged.",
    "Експортувати всі ваші записи? У файлі буде все, що ви записали."
  )
  def confirmFromApp =
    tr(
      "The app asked for an export of all your records. The file will contain everything you logged. Export now?",
      "Застосунок запросив експорт усіх ваших записів. У файлі буде все, що ви записали. Експортувати зараз?"
    )
  def confirmSummaryFromApp =
    tr("The app asked for a record summary. Make it now?", "Застосунок запросив зведення записів. Створити його зараз?")
  def summaryChoice =
    tr(
      "Record summary: a plain record of what you logged, as a PDF. It is not a clinical report. Which period?",
      "Зведення записів: простий перелік того, що ви записали, у форматі PDF. Це не медичний звіт. За який період?"
    )

  def ExportMyData = tr("Export my data", "Експортувати мої дані")
  def RecordSummary = tr("Record summary", "Зведення записів")
  def MakeIt = tr("Make it", "Створити")
  def NotNow = tr("Not now", "Не зараз")
  def DownloadInApp = tr("Download in the app", "Завантажити в застосунку")
  def SendHere = tr("Send the file to this chat", "Надіслати файл у чат")
  def IUnderstand = tr("I understand", "Розумію")
  def SendFile = tr("Send the file", "Надіслати файл")
  def Last30Days = tr("Last 30 days", "Останні 30 днів")
  def Last90Days = tr("Last 90 days", "Останні 90 днів")
  def Last12Months = tr("Last 12 months", "Останні 12 місяців")

  def preparing = tr(
    "Preparing your file. A message follows when it is ready.",
    "Готую ваш файл. Коли він буде готовий, надійде повідомлення."
  )
  def cancelled = tr("Nothing was exported.", "Нічого не експортовано.")
  def sending = tr("Sending the file to this chat.", "Надсилаю файл у цей чат.")

  /** `privacy.export.ready`. */
  def ready = tr(
    "Your export is ready. The link works once and expires in 15 minutes.",
    "Ваш експорт готовий. Посилання спрацює один раз і діє 15 хвилин."
  )
  def summaryReady = tr(
    "Your record summary is ready. The link works once and expires in 15 minutes.",
    "Ваше зведення записів готове. Посилання спрацює один раз і діє 15 хвилин."
  )
  def failed = tr(
    "The file could not be made. Nothing was sent. Try again later.",
    "Не вдалося створити файл. Нічого не надіслано. Спробуйте пізніше."
  )
  def gone = tr(
    "This file has expired. Send /export to make a new one.",
    "Термін дії цього файлу минув. Надішліть /export, щоб створити новий."
  )

  /** `privacy.export.chat_warning`. */
  def chatWarning =
    tr(
      "Sending the file to this chat stores all your records in Telegram's cloud and on your devices. ",
      "Якщо надіслати файл у цей чат, усі ваші записи збережуться в хмарі Telegram і на ваших пристроях. "
    ) +
      tr("Dosegram cannot remove it later.", "Dosegram не зможе згодом його видалити.")
  def chatConfirm = tr("Send the file to this chat now?", "Надіслати файл у цей чат зараз?")

  /** `privacy.export.limit`. */
  def limit(next: Instant, zone: ZoneId): String =
    tr(
      s"You can export 3 times in 24 hours. The next export is possible at ${Clock.withZone(zone).format(next)} on ",
      s"Експортувати можна 3 рази за 24 години. Наступний експорт буде можливий о ${Clock.withZone(zone).format(next)}, "
    ) +
      s"${DayMonth.withZone(zone).format(next)}."

  def exportCaption(at: Instant, zone: ZoneId): String =
    tr(
      s"Your Dosegram export of ${Day.withZone(zone).format(at)}. Contains health records.",
      s"Ваш експорт Dosegram від ${Day.withZone(zone).format(at)}. Містить записи про здоров’я."
    )

  /** VISUALS DOC-01, delivery. */
  def summaryCaption(period: DateRange): String =
    tr(
      s"Your record summary for ${periodText(period)}. Contains health records.",
      s"Ваше зведення записів за ${periodText(period)}. Містить записи про здоров’я."
    )

  // ---------- the record summary document (VISUALS DOC-01) ----------

  /** `doc.record_summary.title`: its only name on every surface. */
  def summaryTitle = tr("Record summary", "Зведення записів")
  def notMedicalAdvice = tr("Recorded entries only. Not medical advice.", "Лише записані дані. Не є медичною порадою.")
  def scopeStatement =
    tr(
      "This document lists entries recorded by the user in Dosegram. It is not a medical record and contains no ",
      "Цей документ перелічує записи, які користувач зробив у Dosegram. Це не медична документація, і в ньому немає "
    ) +
      tr("medical advice or interpretation.", "медичних порад чи тлумачень.")

  def periodText(period: DateRange): String =
    tr(
      s"${Day.format(period.start)} to ${Day.format(period.end)}",
      s"з ${Day.format(period.start)} до ${Day.format(period.end)}"
    )

  def generated(at: Instant, zone: ZoneId): String =
    tr(
      s"Made on ${Day.withZone(zone).format(at)} at ${Clock.withZone(zone).format(at)}, time zone ${zone.getId}.",
      s"Створено ${Day.withZone(zone).format(at)} о ${Clock.withZone(zone).format(at)}, часовий пояс ${zone.getId}."
    )

  def includes(sections: List[String]): String =
    tr(s"Sections: ${sections.mkString(", ")}.", s"Розділи: ${sections.mkString(", ")}.")

  def date(day: LocalDate): String = Day.format(day)

  def sectionScope = tr("Scope", "Обсяг")
  def sectionMedications = tr("Medications", "Ліки")
  def sectionMedicationRecords = tr("Medication records", "Записи про ліки")
  def sectionSleep = tr("Sleep", "Сон")
  def sectionMood = tr("Mood", "Настрій")
  def sectionWater = tr("Water", "Вода")
  def sectionFood = tr("Food", "Їжа")
  def sectionDefinitions = tr("Definitions", "Визначення")

  def noMedications = tr("No medication was recorded in this period.", "За цей період ліків не записано.")
  def noRecords = tr("No entry was recorded in this period.", "За цей період записів немає.")
  def notShownWithoutConsent = tr("Not included: this area is turned off.", "Не включено: цей розділ вимкнено.")

  def medicationHeader = tr(
    List("Medication", "Dose", "Schedule", "Status", "Instructions"),
    List("Ліки", "Доза", "Розклад", "Стан", "Інструкції")
  )
  def doseHeader = tr(
    List("Month", "Scheduled", "On time", "Late", "Skipped", "Not confirmed", "No record"),
    List("Місяць", "Заплановано", "Вчасно", "Пізніше", "Пропущено", "Не підтверджено", "Немає запису")
  )
  def sleepHeader = tr(List("Month", "Nights", "Mean length"), List("Місяць", "Ночі", "Середня тривалість"))
  def moodHeader = tr(List("Month", "Check-ins", "Days with a check-in"), List("Місяць", "Позначки", "Дні з позначкою"))
  def moodStepHeader = tr(List("Recorded step", "Check-ins"), List("Записаний рівень", "Позначки"))
  def waterHeader = tr(
    List("Month", "Days recorded", "Total", "Mean per recorded day", "Goal days"),
    List("Місяць", "Дні із записами", "Усього", "У середньому за день із записом", "Дні з досягнутою ціллю")
  )
  def foodHeader = tr(
    List("Month", "Days with a meal", "Meals logged", "Breakfast days", "Lunch days", "Dinner days"),
    List("Місяць", "Дні з прийомом їжі", "Записані прийоми їжі", "Дні зі сніданком", "Дні з обідом", "Дні з вечерею")
  )

  def taken(taken: Int, resolved: Int, percent: Option[Int]): String =
    percent match
      case Some(value) =>
        tr(
          s"Taken: $taken of $resolved resolved doses ($value%).",
          s"Прийнято: $taken з $resolved доз із відповіддю ($value%)."
        )
      case None =>
        tr(
          s"Taken: $taken of $resolved resolved doses. Too few doses for a percentage.",
          s"Прийнято: $taken з $resolved доз із відповіддю. Замало доз, щоб рахувати відсоток."
        )

  def asNeeded(count: Int): String = tr(s"As-needed doses logged: $count.", s"Записано доз за потреби: $count.")

  /** An as-needed schedule in words, after a medication's name: "Ibuprofen, as needed". */
  def asNeededSchedule: String = tr("as needed", "за потреби")

  // The status column of the medication table.
  def statusActive: String = tr("Active", "Активно")
  def statusPaused: String = tr("Paused", "Призупинено")
  def statusArchived: String = tr("Archived", "В архіві")
  def minutes(total: Int): String = tr(s"${total / 60} h ${total % 60} min", s"${total / 60} год ${total % 60} хв")
  def millilitres(total: Int): String = tr(f"$total%,d ml".replace(',', ' '), f"$total%,d мл".replace(',', ' '))
  def goalDays(days: Int, of: Int): String = tr(s"$days of $of", s"$days з $of")
  def noGoal = tr("No goal", "Без цілі")
  def tooFew = tr("Too few", "Замало")

  def definitions: List[String] =
    Lang.current match
      case Lang.En => EnglishDefinitions
      case Lang.Uk => UkrainianDefinitions

  private val UkrainianDefinitions: List[String] = List(
    "Заплановано: дози, які розклад передбачав у цьому періоді. Вчасно й пізніше: дози, записані як прийняті, " +
      "у межах вікна розкладу або після нього. Не підтверджено: дози без відповіді до закриття вікна. Немає " +
      "запису: дози тих годин, коли нагадування не вдалося надіслати.",
    "Відсоток прийнятих: дози, прийняті вчасно чи пізніше, поділені на дози прийняті, пропущені або не " +
      "підтверджені. Показується лише тоді, коли враховано щонайменше 5 доз.",
    "Сон: основні періоди сну, записані з обома часовими позначками; денний сон не враховується. Середня " +
      "тривалість рахується за записаними ночами місяця.",
    "Вода: сума записів кожного дня. Дні без записів не рахуються як нуль.",
    "Настрій: позначки так, як їх записано, за п’ятьма рівнями від «Дуже неприємно» до «Дуже приємно».",
    "Їжа: записані прийоми їжі охоплюють кожен запис, перекуси теж. День зі сніданком, обідом чи вечерею має " +
      "щонайменше один запис цього прийому їжі. Що саме ви їли, у зведення не входить."
  )

  private val EnglishDefinitions: List[String] = List(
    "Scheduled: doses a schedule planned in the period. On time and late: doses recorded as taken, inside or after " +
      "the window of the schedule. Not confirmed: doses with no answer before the window closed. No record: doses " +
      "of hours when reminders could not be sent.",
    "Taken percentage: doses taken on time or late, divided by doses taken, skipped or not confirmed. It is shown " +
      "only when at least 5 doses count.",
    "Sleep: main sleep periods recorded with both times; naps are not counted. Mean length is over the nights " +
      "recorded in the month.",
    "Water: the sum of the entries of each day. Days without an entry are not counted as zero.",
    "Mood: check-ins as recorded, on the five steps from Very unpleasant to Very pleasant.",
    "Food: meals logged counts every meal entry, snacks included. A breakfast, lunch or dinner day has at least one " +
      "entry of that meal. What was eaten is not part of this summary."
  )

  def entries: List[CopyEntry] = List(
    CopyEntry("privacy.export.confirm", confirm),
    CopyEntry("privacy.export.confirm_app", confirmFromApp),
    CopyEntry("privacy.export.confirm_summary_app", confirmSummaryFromApp),
    CopyEntry("privacy.export.summary_choice", summaryChoice),
    CopyEntry("privacy.export.preparing", preparing),
    CopyEntry("privacy.export.cancelled", cancelled),
    CopyEntry("privacy.export.sending", sending),
    CopyEntry("privacy.export.ready", ready),
    CopyEntry("privacy.export.summary_ready", summaryReady),
    CopyEntry("privacy.export.failed", failed),
    CopyEntry("privacy.export.gone", gone),
    CopyEntry("privacy.export.chat_warning", chatWarning),
    CopyEntry("privacy.export.chat_confirm", chatConfirm),
    CopyEntry("privacy.export.limit", limit(Instant.parse("2026-11-14T12:32:00Z"), ZoneId.of("UTC"))),
    CopyEntry("privacy.export.caption", exportCaption(Instant.parse("2026-11-14T12:32:00Z"), ZoneId.of("UTC"))),
    CopyEntry(
      "doc.record_summary.caption",
      summaryCaption(DateRange.between(LocalDate.parse("2026-08-13"), LocalDate.parse("2026-11-10")))
    ),
    CopyEntry("doc.record_summary.title", summaryTitle),
    CopyEntry("doc.record_summary.not_advice", notMedicalAdvice),
    CopyEntry("doc.record_summary.scope", scopeStatement),
    CopyEntry("doc.record_summary.taken", taken(95, 98, Some(97))),
    CopyEntry("doc.record_summary.taken_few", taken(3, 4, None)),
    CopyEntry("doc.record_summary.as_needed", asNeeded(2)),
    CopyEntry("doc.record_summary.as_needed_schedule", asNeededSchedule),
    CopyEntry("doc.record_summary.status.active", statusActive),
    CopyEntry("doc.record_summary.status.paused", statusPaused),
    CopyEntry("doc.record_summary.status.archived", statusArchived),
    CopyEntry("doc.record_summary.no_medications", noMedications),
    CopyEntry("doc.record_summary.no_records", noRecords),
    CopyEntry("doc.record_summary.consent_off", notShownWithoutConsent)
  ) ++ List(
    "export_my_data" -> ExportMyData,
    "record_summary" -> RecordSummary,
    "make_it" -> MakeIt,
    "download_in_the_app" -> DownloadInApp,
    "send_the_file_to_this_chat" -> SendHere,
    "i_understand" -> IUnderstand,
    "send_the_file" -> SendFile
  ).map((key, label) => CopyEntry(s"privacy.export.button.$key", label)) ++ definitions.zipWithIndex.map(
    (text, index) => CopyEntry(s"doc.record_summary.definition.$index", text)
  )
end ExportCopy
