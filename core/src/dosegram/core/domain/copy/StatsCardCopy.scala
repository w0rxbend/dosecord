package dosegram.core.domain.copy

import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.insights.TrendPeriod

import java.time.LocalDate

/** The controls of the `/stats` card (BOT_UX section 12.5). The figures themselves are [[StatsCopy]]. */
object StatsCardCopy:

  def ShowAsImage = tr("Show as image", "Показати як зображення")

  /** IMG-20 on the seven-day card. The words are the card's title. */
  def WaterWeek = tr("Water, 7 days", "Вода за 7 днів")

  /** IMG-21 on the seven-day card. The words are the card's title. */
  def SleepWeek = tr("Sleep, 7 nights", "Сон за 7 ночей")

  /** IMG-22 on the seven-day card. The words are the card's title. */
  def MoodWeekday = tr("Mood by weekday", "Настрій за днями тижня")

  /** The way from the 30 days to their trends (ROADMAP G9.4, G9.5); the words are the trends card's title. */
  def Trends = tr("Trends", "Динаміка")

  def usage = tr(
    "Send /stats, /stats today, /stats 7, /stats 30, /stats trends, /stats month, /stats year or /stats meds.",
    "Надішліть /stats, /stats today, /stats 7, /stats 30, /stats trends, /stats month, /stats year або /stats meds."
  )
  def imageUnavailable = tr(
    "The image is not available right now. The figures above are complete.",
    "Зображення зараз недоступне. Цифри вище повні."
  )

  def period(period: StatsPeriod): String =
    period match
      case StatsPeriod.Today      => tr("Today", "Сьогодні")
      case StatsPeriod.Last7Days  => tr("7 days", "7 днів")
      case StatsPeriod.Last30Days => tr("30 days", "30 днів")

  /** A period button of the trends: "30 days", "90 days". */
  def trendsPeriod(period: TrendPeriod): String = tr(s"${period.days} days", UkNouns.Days.count(period.days))

  // ---------- /stats meds (supervisor decision, 2026-11-27; VISUALS IMG-07) ----------

  /** The way from `/stats` to the medications one by one; the words are the strip card's title. */
  def ByMedication = tr("By medication", "За ліками")

  /** The period buttons of `/stats meds`: "7 days", "14 days". */
  def medsPeriod(days: Int): String = tr(s"$days days", UkNouns.Days.count(days))

  def medsTitle(days: Int, end: LocalDate): String =
    val to = Formats.dayMonth.format(end)
    tr(s"$ByMedication · last $days days to $to", s"$ByMedication · останні ${UkNouns.Days.count(days)} до $to")

  /** One scheduled medication's doses by answer, as `/stats meds` counts them. */
  final case class MedsTally(taken: Int, skipped: Int, missed: Int, open: Int, noRecord: Int):
    /** The denominator: an open dose is still to answer and a dose with no record is not counted. */
    def answered: Int = taken + skipped + missed

  /** One scheduled medication: the doses recorded as taken of those answered, then the skipped, the not confirmed, the
    * ones still open and those with no record, each only when there is one. Doses with no answer are never counted
    * against the user. The strip image (IMG-07) states the same figures in the same order on its card and in its
    * caption (supervisor decision, 2026-11-28).
    */
  def medsRow(position: Int, name: String, tally: MedsTally): String =
    val headline =
      if tally.answered == 0 then tr("no answered dose yet", "ще немає доз із відповіддю")
      else tr(s"${tally.taken} of ${tally.answered} taken", s"прийнято ${tally.taken} з ${tally.answered}")
    val rest = List(
      Option.when(tally.skipped > 0)(tr(s"skipped ${tally.skipped}", s"пропущено ${tally.skipped}")),
      Option.when(tally.missed > 0)(tr(s"not confirmed ${tally.missed}", s"не підтверджено ${tally.missed}")),
      Option.when(tally.open > 0)(
        tr(s"${tally.open} still to answer, not counted yet", s"ще без відповіді — ${tally.open}, поки не враховано")
      ),
      Option.when(tally.noRecord > 0)(
        tr(s"${tally.noRecord} with no record, not counted", s"без запису — ${tally.noRecord}, не враховано")
      )
    ).flatten
    s"$position. $name: ${(headline :: rest).mkString(" · ")}"

  /** One medication taken as needed: its logs and the days they fall on, with no rate (VISUALS section 6.8). */
  def medsAsNeededRow(position: Int, name: String, logs: Int, days: Int): String =
    tr(
      s"$position. $name (as needed): ${StatsCopy.count(logs, "log")} on ${StatsCopy.count(days, "day")}",
      s"$position. $name (за потреби): ${UkNouns.Entries.count(logs)} за ${UkNouns.Days.count(days)}"
    )

  def medsNone = tr(
    "No medication in your list yet. Add one with /meds.",
    "У вашому списку ще немає ліків. Додайте їх через /meds."
  )

  /** Under the rows: what the numbers are, so that the image's "Item 1" reads as the first row. */
  def medsNote = tr(
    "Counts of what you recorded. In the image the rows are numbered in the same order, without names.",
    "Це підрахунок того, що ви записали. На зображенні рядки пронумеровано в тому самому порядку, без назв."
  )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("statscard.show_as_image", ShowAsImage),
      CopyEntry("statscard.water_week", WaterWeek),
      CopyEntry("statscard.sleep_week", SleepWeek),
      CopyEntry("statscard.mood_weekday", MoodWeekday),
      CopyEntry("statscard.trends", Trends),
      CopyEntry("statscard.usage", usage),
      CopyEntry("statscard.image_unavailable", imageUnavailable),
      CopyEntry("statscard.by_medication", ByMedication),
      CopyEntry("statscard.meds.period", medsPeriod(14)),
      CopyEntry("statscard.meds.title", medsTitle(7, LocalDate.parse("2026-11-26"))),
      CopyEntry("statscard.meds.row", medsRow(1, "Vitamin D", MedsTally(6, 1, 0, 1, 0))),
      CopyEntry("statscard.meds.row.missed", medsRow(2, "Magnesium", MedsTally(7, 0, 2, 0, 1))),
      CopyEntry("statscard.meds.row.empty", medsRow(1, "Vitamin D", MedsTally(0, 0, 0, 1, 0))),
      CopyEntry("statscard.meds.as_needed", medsAsNeededRow(3, "Ibuprofen", 3, 2)),
      CopyEntry("statscard.meds.none", medsNone),
      CopyEntry("statscard.meds.note", medsNote)
    ) ++ StatsPeriod.values.toList.map(value => CopyEntry(s"statscard.period.${value.days}", period(value))) ++
      TrendPeriod.Trends.map(value => CopyEntry(s"statscard.trends.${value.days}", trendsPeriod(value)))
end StatsCardCopy
