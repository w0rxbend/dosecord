package dosegram.render.copy

import dosegram.render.card.YearStripData
import dosegram.render.card.YearStripRow
import dosegram.render.tokens.Domain

/** The words of the one-year card (VISUALS IMG-30, supervisor decision, 2026-11-28): kept apart from [[ImageCopy]] so
  * the card's catalogue is one block per language. Every sentence records days with a record; none judges a week.
  */
trait YearImageCopy:
  def title(year: Int): String
  def subtitle: String
  def monthInitial(month: Int): String
  def fillMark: String
  def emptyMark: String
  def weekNote: String
  def fact(days: Int, total: Int): String
  def text(data: YearStripData): String
  def discreet: String

object EnglishYearImageCopy extends YearImageCopy:
  private val Initials = Vector("J", "F", "M", "A", "M", "J", "J", "A", "S", "O", "N", "D")
  private val Months = Vector("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

  def title(year: Int): String = s"Your year: $year"
  def subtitle: String = "Days with a record, week by week"
  def monthInitial(month: Int): String = Initials(month - 1)
  def fillMark: String = "Taller: more days with a record that week"
  def emptyMark: String = "No record that week"
  def weekNote: String = "Each cell: one week, Monday to Sunday"
  def fact(days: Int, total: Int): String = s"$days of ${plural(total, "day")} with records"

  def text(data: YearStripData): String =
    val rows = data.rows.map(row => s"${area(row)}${months(row)}.")
    val summary = s"${data.daysWithRecords} of ${plural(data.days, "day")} have a record."
    (s"${data.year}, your year in records, ${EnglishImageCopy.dateRange(data.from, data.to)}. $summary" +: rows)
      .mkString(" ")

  def discreet: String = "Your year in records."

  private def area(row: YearStripRow): String =
    row.domain match
      case Domain.Mood  => s"Mood: ${plural(row.recordedDays, "check-in day")}"
      case Domain.Sleep => s"Sleep: ${plural(row.recordedDays, "night")}"
      case other        => s"${EnglishImageCopy.domainName(other)}: ${plural(row.recordedDays, "day")}"

  private def months(row: YearStripRow): String =
    val recorded = row.monthly.zipWithIndex.collect { case (days, index) if days > 0 => s"${Months(index)} $days" }
    if recorded.isEmpty then "" else recorded.mkString(" (", ", ", ")")

  private def plural(n: Int, noun: String): String = if n == 1 then s"1 $noun" else s"$n ${noun}s"

object UkrainianYearImageCopy extends YearImageCopy:
  private val Initials = Vector("С", "Л", "Б", "К", "Т", "Ч", "Л", "С", "В", "Ж", "Л", "Г")
  private val Months = Vector("січ", "лют", "бер", "кві", "тра", "чер", "лип", "сер", "вер", "жов", "лис", "гру")

  def title(year: Int): String = s"Ваш рік: $year"
  def subtitle: String = "Дні із записами, тиждень за тижнем"
  def monthInitial(month: Int): String = Initials(month - 1)
  def fillMark: String = "Вища клітинка: більше днів із записами того тижня"
  def emptyMark: String = "Того тижня записів немає"
  def weekNote: String = "Клітинка: один тиждень, з понеділка до неділі"
  def fact(days: Int, total: Int): String = s"Днів із записами: $days з $total"

  def text(data: YearStripData): String =
    val rows = data.rows.map(row => s"${area(row)}${months(row)}.")
    val summary = s"Записи є за ${data.daysWithRecords} з ${data.days} ${daysOf(data.days)}."
    (s"${data.year}, ваш рік у записах, ${UkrainianImageCopy.dateRange(data.from, data.to)}. $summary" +: rows)
      .mkString(" ")

  def discreet: String = "Ваш рік у записах."

  private def area(row: YearStripRow): String =
    val n = row.recordedDays
    row.domain match
      case Domain.Mood  => s"Настрій: $n ${form(n, "день із позначкою", "дні з позначками", "днів із позначками")}"
      case Domain.Sleep => s"Сон: $n ${form(n, "ніч", "ночі", "ночей")}"
      case other        => s"${UkrainianImageCopy.domainName(other)}: $n ${form(n, "день", "дні", "днів")}"

  private def months(row: YearStripRow): String =
    val recorded = row.monthly.zipWithIndex.collect { case (days, index) if days > 0 => s"${Months(index)} $days" }
    if recorded.isEmpty then "" else recorded.mkString(" (", ", ", ")")

  /** "з 312 днів": the genitive after a count of the whole. */
  private def daysOf(n: Int): String = if n % 10 == 1 && n % 100 != 11 then "дня" else "днів"

  private def form(n: Int, one: String, few: String, many: String): String =
    if n % 10 == 1 && n % 100 != 11 then one
    else if (2 to 4).contains(n % 10) && !(12 to 14).contains(n % 100) then few
    else many
