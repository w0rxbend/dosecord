package dosegram.core.domain.input

import dosegram.contracts.HhMm

import scala.util.matching.Regex

/** The one typed time-of-day grammar every chat step uses (BOT_UX section 15.6). It is lenient about spelling and
  * strict about meaning: a value it cannot read without guessing is refused with the part it did not understand.
  *
  * Readings, decided once:
  *   - a number without am or pm is on the 24-hour clock: "7" is 07:00, never 19:00;
  *   - three or four digits are hours and minutes: "930" is 09:30, "2130" is 21:30;
  *   - two numbers separated only by a space are one time when the second has two digits and starts with 0, is 15, 30
  *     or 45, or cannot be an hour (24 to 59): "9 00", "9 30", "7 05", "9 50"; otherwise they are two times: "8 20" is
  *     08:00 and 20:00;
  *   - a comma always separates times, so "9,30" is not 09:30;
  *   - "24:00" and "24" are midnight (00:00); "12am" is 00:00 and "12pm" is 12:00.
  */
object TimeOfDay:

  /** The chat accepts at most 6 times per day (BUX-MED-03); more are added in the Mini App. */
  val MaxTimes = 6

  enum Rejection:
    /** Nothing that could be a time was typed. */
    case Empty

    /** `part` (normalised, as the user wrote it) is not a time. */
    case NotATime(part: String)

    /** More than `max` distinct times. */
    case TooMany(max: Int)

  private val ListSeparator: Regex = "[,;/&+\\n]|\\band\\b|\\bthen\\b".r
  private val Fillers = Set("at", "around", "about", "approx", "approximately", "by", "also", "every", "day", "daily")
  private val Clock: Regex = "^(\\d{1,2})[:.h](\\d{2})(am|pm)?$".r
  private val HourH: Regex = "^(\\d{1,2})h(am|pm)?$".r
  private val Digits: Regex = "^(\\d{1,4})(am|pm)?$".r
  private val QuarterMinutes = Set("15", "30", "45")

  /** One time: the reading of a single time when exactly one was typed. */
  def parseOne(text: String): Either[Rejection, HhMm] =
    parseList(text).flatMap:
      case List(single) => Right(single)
      case _            => Left(Rejection.NotATime(InputText.quoted(InputText.normalise(text))))

  /** One or more times, sorted, without repeats. */
  def parseList(text: String): Either[Rejection, List[HhMm]] =
    // A line break separates times like a comma; normalising would fold it into a space.
    val normalised = InputText
      .normalise(text.replace('\n', ','))
      .replace("o'clock", " ")
      .replace("o’clock", " ")
      .replace("oclock", " ")
      .replaceAll("([ap])\\.m\\.?", "$1m")
      .replace('@', ' ')
    val chunks = ListSeparator.split(normalised).toList.map(chunkTokens).filter(_.nonEmpty)
    if chunks.isEmpty then Left(Rejection.Empty)
    else
      val read = chunks.foldLeft[Either[Rejection, List[HhMm]]](Right(Nil)): (acc, tokens) =>
        acc.flatMap(found => readTokens(tokens).map(found ++ _))
      read.flatMap: found =>
        val distinct = found.distinct.sortBy(_.value)
        if distinct.size > MaxTimes then Left(Rejection.TooMany(MaxTimes)) else Right(distinct)

  private def chunkTokens(chunk: String): List[String] =
    InputText
      .withoutTrailingPunctuation(chunk.trim)
      .split(" ")
      .toList
      .map(InputText.withoutTrailingPunctuation)
      .filter(token => token.nonEmpty && !Fillers.contains(token))

  /** A chunk's tokens left to right; "am"/"pm" on its own applies to the time before it. */
  private def readTokens(tokens: List[String]): Either[Rejection, List[HhMm]] =
    tokens match
      case Nil                                                                 => Right(Nil)
      case hour :: minute :: rest if isBareHour(hour) && isMinuteToken(minute) =>
        val (meridiem, after) = takeMeridiem(rest)
        for
          time <- clock(hour.toInt, minute.toInt, meridiem, s"$hour $minute")
          more <- readTokens(after)
        yield time :: more
      case token :: rest =>
        val (meridiem, after) = takeMeridiem(rest)
        for
          time <- readToken(token, meridiem)
          more <- readTokens(after)
        yield time :: more

  private def takeMeridiem(tokens: List[String]): (Option[String], List[String]) =
    tokens match
      case (m @ ("am" | "pm")) :: rest => (Some(m), rest)
      case other                       => (None, other)

  private def isBareHour(token: String): Boolean =
    token.length <= 2 && token.forall(_.isDigit)

  private def isMinuteToken(token: String): Boolean =
    token.length == 2 && token.forall(_.isDigit) &&
      (token.startsWith("0") || QuarterMinutes.contains(token) || (token.toInt >= 24 && token.toInt <= 59))

  private def readToken(token: String, trailing: Option[String]): Either[Rejection, HhMm] =
    def withMeridiem(own: String | Null): Option[String] = Option(own).orElse(trailing)
    token match
      case "noon" | "midday" if trailing.isEmpty => Right(HhMm.unsafe("12:00"))
      case "midnight" if trailing.isEmpty        => Right(HhMm.unsafe("00:00"))
      case Clock(h, m, own)                      => clock(h.toInt, m.toInt, withMeridiem(own), token)
      case HourH(h, own)                         => clock(h.toInt, 0, withMeridiem(own), token)
      case Digits(digits, own)                   =>
        val (h, m) = digits.length match
          case 1 | 2 => (digits.toInt, 0)
          case 3     => (digits.take(1).toInt, digits.drop(1).toInt)
          case _     => (digits.take(2).toInt, digits.drop(2).toInt)
        clock(h, m, withMeridiem(own), token)
      case other => Left(Rejection.NotATime(InputText.quoted(other)))

  private def clock(hour: Int, minute: Int, meridiem: Option[String], shown: String): Either[Rejection, HhMm] =
    val reject = Left(Rejection.NotATime(InputText.quoted(meridiem.filterNot(shown.endsWith).fold(shown)(shown + _))))
    val hour24 = meridiem match
      case None if hour == 24 && minute == 0     => Some(0)
      case None if hour <= 23                    => Some(hour)
      case Some("am") if hour >= 1 && hour <= 12 => Some(hour % 12)
      case Some("pm") if hour >= 1 && hour <= 12 => Some(hour % 12 + 12)
      case _                                     => None
    hour24 match
      case Some(h) if minute <= 59 => Right(HhMm.unsafe(f"$h%02d:$minute%02d"))
      case _                       => reject
