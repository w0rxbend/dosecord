package dosegram.core.domain.insights

import java.time.LocalDate

/** The domains whose acts of logging may be counted (PRODUCT_SPEC section 8.14). Medication is not one of them, and no
  * measured value is: a count of doses, of litres or of hours would reward the value, not the logging (R-GAM-2).
  */
enum LoggingDomain:
  case Water, Mood, Sleep, Food

/** On how many days of the week the domain has a record (R-GAM-1): a count of successes in a window, so nothing resets
  * and nothing can be lost.
  */
final case class WeeklyConsistency(domain: LoggingDomain, hitDays: Int, days: Int)

/** An act of logging reaching a round count in one domain (R-GAM-4): one line of the weekly report, never a message. */
final case class LoggingMilestone(domain: LoggingDomain, count: Int)

/** The counts of section 8.14, derived at read time from the records (VISUALS section 7.3). */
object Consistency:

  /** The milestone counts of VISUALS section 7.3. */
  val MilestoneCounts: List[Int] = List(10, 50, 100)

  /** Each domain's hit days among the days of the window, in the domain order. A domain without a record in the window
    * is left out: a week of nothing is a gate, not "0 of 7" (VA-ME-20).
    */
  def weekly(recorded: Map[LoggingDomain, Set[LocalDate]], window: DateRange): List[WeeklyConsistency] =
    LoggingDomain.values.toList.flatMap: domain =>
      val hits = recorded.getOrElse(domain, Set.empty).count(window.contains)
      Option.when(hits > 0)(WeeklyConsistency(domain, hits, window.days))

  /** The milestone counts each domain passed between `before` and `after`, the counts at the start and the end of the
    * reported week. A count added and removed again within the week passes nothing.
    */
  def milestones(before: Map[LoggingDomain, Int], after: Map[LoggingDomain, Int]): List[LoggingMilestone] =
    LoggingDomain.values.toList.flatMap: domain =>
      val from = before.getOrElse(domain, 0)
      val to = after.getOrElse(domain, 0)
      MilestoneCounts.filter(count => from < count && count <= to).map(LoggingMilestone(domain, _))
end Consistency
