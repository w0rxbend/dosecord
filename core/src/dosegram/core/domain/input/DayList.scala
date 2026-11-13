package dosegram.core.domain.input

import dosegram.contracts.Weekday

/** A typed list of weekdays (BOT_UX section 15.6): names in any length ("mon", "Monday", "tues", "Saturdays"), numbers
  * 1 (Monday) to 7 (Sunday), ranges ("mon-fri", "fri-mon" wraps), and "every day", "daily", "weekdays", "weekends". The
  * result is in Monday-first order without repeats.
  */
object DayList:

  enum Rejection:
    case Empty
    case NotADay(part: String)

  private val all: List[Weekday] = Weekday.values.toList
  private val Fillers = Set("on", "every", "each", "and", "the", "of", "week")

  private val Phrases: List[(String, String)] = List(
    "every day" -> "everyday",
    "all days" -> "everyday",
    "work days" -> "weekdays",
    "week days" -> "weekdays"
  )

  private val Groups: Map[String, List[Weekday]] = Map(
    "everyday" -> all,
    "daily" -> all,
    "all" -> all,
    "weekdays" -> all.take(5),
    "weekday" -> all.take(5),
    "workdays" -> all.take(5),
    "weekends" -> all.drop(5),
    "weekend" -> all.drop(5)
  )

  private val Names: Map[String, Weekday] =
    List(
      Weekday.Mon -> List("mo", "mon", "monday"),
      Weekday.Tue -> List("tu", "tue", "tues", "tuesday"),
      Weekday.Wed -> List("we", "wed", "weds", "wednesday"),
      Weekday.Thu -> List("th", "thu", "thur", "thurs", "thursday"),
      Weekday.Fri -> List("fr", "fri", "friday"),
      Weekday.Sat -> List("sa", "sat", "saturday"),
      Weekday.Sun -> List("su", "sun", "sunday")
    ).flatMap((day, names) => names.map(_ -> day)).toMap

  def parse(text: String): Either[Rejection, List[Weekday]] =
    val spoken = Phrases.foldLeft(InputText.withoutTrailingPunctuation(InputText.normalise(text))) {
      case (acc, (phrase, word)) => acc.replace(phrase, word)
    }
    val tokens = spoken.split("[\\s,;/&+]+").toList.filter(t => t.nonEmpty && !Fillers.contains(t))
    if tokens.isEmpty then Left(Rejection.Empty)
    else
      tokens
        .foldLeft[Either[Rejection, List[Weekday]]](Right(Nil)): (acc, token) =>
          acc.flatMap(found => readToken(token).map(found ++ _))
        .map(found => found.distinct.sortBy(_.ordinal))

  private def readToken(text: String): Either[Rejection, List[Weekday]] =
    Groups.get(text).orElse(day(text).map(List(_))).orElse(range(text)) match
      case Some(days) => Right(days)
      case None       => Left(Rejection.NotADay(InputText.quoted(text)))

  private def day(text: String): Option[Weekday] =
    text.toIntOption
      .filter(n => n >= 1 && n <= 7)
      .map(n => all(n - 1))
      .orElse(Names.get(text))
      .orElse(Option.when(text.endsWith("s"))(text.dropRight(1)).filter(_.length > 3).flatMap(Names.get))

  private def range(text: String): Option[List[Weekday]] =
    text.split('-') match
      case Array(from, to) =>
        for start <- day(from); end <- day(to)
        yield
          val length = Math.floorMod(end.ordinal - start.ordinal, all.size) + 1
          List.tabulate(length)(i => all((start.ordinal + i) % all.size))
      case _ => None
