package dosegram.core.domain.input

import scala.math.BigDecimal.RoundingMode
import scala.util.matching.Regex

/** A typed snooze length in minutes (BOT_UX section 15.6): "10", "10m", "1h", "1.5h", "1h30", "half an hour". A bare
  * number is minutes. Fractions of a minute round to the nearest minute.
  */
object SnoozeDuration:

  val MinMinutes = 1
  val MaxMinutes = 1440

  enum Rejection:
    case Empty
    case NotADuration(part: String)
    case OutOfRange(min: Int, max: Int)

  private val Number = "(\\d+(?:\\.\\d+)?|\\d+/\\d+|\\.\\d+)"
  private val Shape: Regex =
    s"^(?:$Number ?(?:h|hr|hrs|hour|hours|год|години|годин|годину))? ?(?:$Number ?(?:m|min|mins|minute|minutes|хв|хвилин|хвилини|хвилину)?)?$$".r
  private val Fillers = Set("for", "in", "another", "snooze", "на", "ще")

  def parse(text: String): Either[Rejection, Int] =
    val words = InputText
      .withoutTrailingPunctuation(InputText.normalise(text))
      .replace("half an hour", "30")
      .replace("пів години", "30")
      .replace("півгодини", "30")
      .replace("an hour", "1h")
      .replace("one hour", "1h")
      .replace(',', '.')
      .split(" ")
      .toList
      .filterNot(Fillers.contains)
    val spoken = words.mkString(" ").trim
    if spoken.isEmpty then Left(Rejection.Empty)
    else
      spoken match
        case Shape(hours, minutes) if hours != null || minutes != null =>
          val total = Option(hours).map(number(_) * 60).getOrElse(BigDecimal(0)) +
            Option(minutes).map(number).getOrElse(BigDecimal(0))
          val rounded = total.setScale(0, RoundingMode.HALF_UP)
          if rounded < MinMinutes || rounded > MaxMinutes then Left(Rejection.OutOfRange(MinMinutes, MaxMinutes))
          else Right(rounded.toInt)
        case _ => Left(Rejection.NotADuration(InputText.quoted(spoken)))

  private def number(text: String): BigDecimal =
    text.split('/') match
      case Array(n, d) if BigDecimal(d) != 0 => BigDecimal(n) / BigDecimal(d)
      case Array(_, _)                       => BigDecimal(MaxMinutes + 1)
      case _                                 => BigDecimal(text)
