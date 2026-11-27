package dosegram.core.domain.insights

/** The domains of the week highlights (VISUALS IMG-25). Medication is never one: a "best day" of dose answers would be
  * the counter of intake that PRODUCT_SPEC R-GAM-2 and VISUALS section 8.3 forbid.
  */
enum HighlightDomain:
  case Water, Food, Mood, Sleep

/** One domain of a seven-day week: the days with a record (indexes 0 to 6 from the week's first day) and, from
  * [[WeekHighlights.MinimumDays]] recorded days, the days that share the highest value with that value.
  */
final case class DayHighlight(domain: HighlightDomain, recorded: List[Int], top: Option[(List[Int], Double)])

/** The highest day of a week per domain (supervisor decision, 2026-11-28): the most water, the most main meals, the
  * highest daily mood, the longest night. It is a fact of the user's own days, stated with its value and every day that
  * shares it, never a score or a rank. Below three recorded days a maximum says nothing about a week, so only the
  * recorded days are given; a highest value of zero (a week of snacks only) is no highlight either.
  */
object WeekHighlights:
  val MinimumDays = 3

  /** `days` holds one value per day of the week, `None` for a day without a record; `None` when no day has one. */
  def of(domain: HighlightDomain, days: List[Option[Double]]): Option[DayHighlight] =
    val recorded = days.zipWithIndex.collect { case (Some(value), index) => index -> value }
    Option.when(recorded.nonEmpty):
      val highest = recorded.map(_._2).max
      val top = Option.when(recorded.size >= MinimumDays && highest > 0)(
        recorded.collect { case (index, value) if value == highest => index } -> highest
      )
      DayHighlight(domain, recorded.map(_._1), top)
