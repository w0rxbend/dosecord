package dosegram.render.copy

import java.time.LocalDate

/** The fixed words of the comparison card (VISUALS IMG-27, supervisor decision, 2026-11-28), one block per language.
  * The figures, the period names and the differences are the caller's, in the words the chat prints; nothing here
  * judges a period.
  */
trait CompareImageCopy:
  def title: String
  def noFigure: String
  def scaleNote: String
  def moodNote: String
  def discreet: String

  /** A period's days under its name, short enough for half the card's width. */
  def dates(from: LocalDate, to: LocalDate): String

object EnglishCompareImageCopy extends CompareImageCopy:
  def title: String = "Two periods side by side"
  def noFigure: String = "No figure: too few recorded days"
  def scaleNote: String = "Each row on its own scale"
  def moodNote: String = "Mood: a dot on the 1 to 5 scale"
  def discreet: String = "Two periods compared."
  def dates(from: LocalDate, to: LocalDate): String =
    if from == to then EnglishImageCopy.dayDate(from) else EnglishImageCopy.dateRange(from, to)

object UkrainianCompareImageCopy extends CompareImageCopy:
  def title: String = "Два періоди поруч"
  def noFigure: String = "Без цифри: замало днів із записами"
  def scaleNote: String = "Кожен рядок у власному масштабі"
  def moodNote: String = "Настрій: точка на шкалі від 1 до 5"
  def discreet: String = "Порівняння двох періодів."

  /** The usual abbreviations of the months in the genitive, for a period across two months: "26 жовт. – 1 листоп.". */
  private val Abbreviated =
    Vector("січ.", "лют.", "берез.", "квіт.", "трав.", "черв.", "лип.", "серп.", "верес.", "жовт.", "листоп.", "груд.")

  def dates(from: LocalDate, to: LocalDate): String =
    if from == to then UkrainianImageCopy.dayDate(from)
    else if from.getMonth == to.getMonth && from.getYear == to.getYear then UkrainianImageCopy.dateRange(from, to)
    else
      val start = s"${from.getDayOfMonth} ${Abbreviated(from.getMonthValue - 1)}"
      s"$start – ${to.getDayOfMonth} ${Abbreviated(to.getMonthValue - 1)}"
