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

  private val Day = DateTimeFormatter.ofPattern("d MMM yyyy", JavaLocale.ENGLISH)
  private val DayMonth = DateTimeFormatter.ofPattern("d MMM", JavaLocale.ENGLISH)
  private val Clock = DateTimeFormatter.ofPattern("HH:mm", JavaLocale.ENGLISH)

  // ---------- chat (BOT_UX section 20.2) ----------

  /** `privacy.export.confirm`. */
  val confirm = "Export all your records? The file will contain everything you logged."
  val confirmFromApp =
    "The app asked for an export of all your records. The file will contain everything you logged. Export now?"
  val confirmSummaryFromApp = "The app asked for a record summary. Make it now?"
  val summaryChoice =
    "Record summary: a plain record of what you logged, as a PDF. It is not a clinical report. Which period?"

  val ExportMyData = "Export my data"
  val RecordSummary = "Record summary"
  val MakeIt = "Make it"
  val NotNow = "Not now"
  val DownloadInApp = "Download in the app"
  val SendHere = "Send the file to this chat"
  val IUnderstand = "I understand"
  val SendFile = "Send the file"
  val Last30Days = "Last 30 days"
  val Last90Days = "Last 90 days"
  val Last12Months = "Last 12 months"

  val preparing = "Preparing your file. A message follows when it is ready."
  val cancelled = "Nothing was exported."
  val sending = "Sending the file to this chat."

  /** `privacy.export.ready`. */
  val ready = "Your export is ready. The link works once and expires in 15 minutes."
  val summaryReady = "Your record summary is ready. The link works once and expires in 15 minutes."
  val failed = "The file could not be made. Nothing was sent. Try again later."
  val gone = "This file has expired. Send /export to make a new one."

  /** `privacy.export.chat_warning`. */
  val chatWarning =
    "Sending the file to this chat stores all your records in Telegram's cloud and on your devices. " +
      "Dosegram cannot remove it later."
  val chatConfirm = "Send the file to this chat now?"

  /** `privacy.export.limit`. */
  def limit(next: Instant, zone: ZoneId): String =
    s"You can export 3 times in 24 hours. The next export is possible at ${Clock.withZone(zone).format(next)} on " +
      s"${DayMonth.withZone(zone).format(next)}."

  def exportCaption(at: Instant, zone: ZoneId): String =
    s"Your Dosegram export of ${Day.withZone(zone).format(at)}. Contains health records."

  /** VISUALS DOC-01, delivery. */
  def summaryCaption(period: DateRange): String =
    s"Your record summary for ${periodText(period)}. Contains health records."

  // ---------- the record summary document (VISUALS DOC-01) ----------

  /** `doc.record_summary.title`: its only name on every surface. */
  val summaryTitle = "Record summary"
  val notMedicalAdvice = "Recorded entries only. Not medical advice."
  val scopeStatement =
    "This document lists entries recorded by the user in Dosegram. It is not a medical record and contains no " +
      "medical advice or interpretation."

  def periodText(period: DateRange): String =
    s"${Day.format(period.start)} to ${Day.format(period.end)}"

  def generated(at: Instant, zone: ZoneId): String =
    s"Made on ${Day.withZone(zone).format(at)} at ${Clock.withZone(zone).format(at)}, time zone ${zone.getId}."

  def includes(sections: List[String]): String = s"Sections: ${sections.mkString(", ")}."

  def date(day: LocalDate): String = Day.format(day)

  val sectionScope = "Scope"
  val sectionMedications = "Medications"
  val sectionMedicationRecords = "Medication records"
  val sectionSleep = "Sleep"
  val sectionMood = "Mood"
  val sectionWater = "Water"
  val sectionFood = "Food"
  val sectionDefinitions = "Definitions"

  val noMedications = "No medication was recorded in this period."
  val noRecords = "No entry was recorded in this period."
  val notShownWithoutConsent = "Not included: this area is turned off."

  val medicationHeader = List("Medication", "Dose", "Schedule", "Status", "Instructions")
  val doseHeader = List("Month", "Scheduled", "On time", "Late", "Skipped", "Not confirmed", "No record")
  val sleepHeader = List("Month", "Nights", "Mean length")
  val moodHeader = List("Month", "Check-ins", "Days with a check-in")
  val moodStepHeader = List("Recorded step", "Check-ins")
  val waterHeader = List("Month", "Days recorded", "Total", "Mean per recorded day", "Goal days")
  val foodHeader = List("Month", "Days with a meal", "Meals logged", "Breakfast days", "Lunch days", "Dinner days")

  def taken(taken: Int, resolved: Int, percent: Option[Int]): String =
    percent match
      case Some(value) => s"Taken: $taken of $resolved resolved doses ($value%)."
      case None        => s"Taken: $taken of $resolved resolved doses. Too few doses for a percentage."

  def asNeeded(count: Int): String = s"As-needed doses logged: $count."
  def minutes(total: Int): String = s"${total / 60} h ${total % 60} min"
  def millilitres(total: Int): String = f"$total%,d ml".replace(',', ' ')
  def goalDays(days: Int, of: Int): String = s"$days of $of"
  val noGoal = "No goal"
  val tooFew = "Too few"

  val definitions: List[String] = List(
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

  val entries: List[CopyEntry] = List(
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
    CopyEntry("doc.record_summary.no_medications", noMedications),
    CopyEntry("doc.record_summary.no_records", noRecords),
    CopyEntry("doc.record_summary.consent_off", notShownWithoutConsent)
  ) ++ List(ExportMyData, RecordSummary, MakeIt, DownloadInApp, SendHere, IUnderstand, SendFile).map(label =>
    CopyEntry(s"privacy.export.button.${label.toLowerCase(JavaLocale.ROOT).replace(' ', '_')}", label)
  ) ++ definitions.zipWithIndex.map((text, index) => CopyEntry(s"doc.record_summary.definition.$index", text))
end ExportCopy
