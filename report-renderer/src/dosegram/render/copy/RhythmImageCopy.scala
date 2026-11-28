package dosegram.render.copy

import java.time.LocalDate

/** The fixed words of the rhythm card (VISUALS IMG-28, supervisor decision, 2026-11-28), one block per language. The
  * summaries and the recorded days are the caller's, in the words the chat prints; nothing here names a target time.
  */
trait RhythmImageCopy:
  def title: String
  def noPattern: String
  def scaleNote: String
  def sleepNote: String
  def discreet: String

  /** The window under the title. */
  def dates(from: LocalDate, to: LocalDate): String

object EnglishRhythmImageCopy extends RhythmImageCopy:
  def title: String = "Rhythm of the day"
  def noPattern: String = "No pattern: fewer than 14 days with records"
  def scaleNote: String = "Height: records in each hour, on the row's scale"
  def sleepNote: String = "Sleep: nights asleep in each hour"
  def discreet: String = "The rhythm of your days."
  def dates(from: LocalDate, to: LocalDate): String = EnglishCompareImageCopy.dates(from, to)

object UkrainianRhythmImageCopy extends RhythmImageCopy:
  def title: String = "Ритм дня"
  def noPattern: String = "Без ритму: менше ніж 14 днів із записами"
  def scaleNote: String = "Висота: записи за годину, у масштабі рядка"
  def sleepNote: String = "Сон: ночі, коли ця година минула уві сні"
  def discreet: String = "Ритм ваших днів."
  def dates(from: LocalDate, to: LocalDate): String = UkrainianCompareImageCopy.dates(from, to)
