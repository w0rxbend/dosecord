package dosegram.core.application.reports

import dosegram.core.application.insights.StatsQueryService
import dosegram.core.domain.copy.ShareCopy
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.StatsPeriod
import dosegram.core.domain.mood.MoodLevel
import dosegram.core.ports.ShareCardView
import dosegram.core.ports.Tx

import java.util.UUID

/** The facts a shareable card may state (VISUALS section 6.18, IMG-17), for the chat's "Share a card" and the Mini
  * App's share button alike: the last seven days, in a fixed order, each under a stable code. Medication is never a
  * fact, and with the gamification switch off the day counts are not offered either (PRODUCT_SPEC R-GAM-5): only the
  * week's mood is.
  */
final class ShareFacts(stats: StatsQueryService):
  import ShareFacts.*

  def of(tx: Tx, accountId: UUID): List[(Int, ShareCardView)] =
    stats
      .summary(tx, accountId, StatsPeriod.Last7Days)
      .toOption
      .toList
      .flatMap: summary =>
        val window = summary.window
        val counts = if tx.accounts.gamificationOf(accountId) then dayCounts(tx, accountId, window) else Nil
        (counts ++ weekMood(tx, accountId, window).toList).map(fact => codeOf(fact) -> fact)

  private def dayCounts(tx: Tx, accountId: UUID, window: DateRange): List[ShareCardView] =
    def marks(days: Set[java.time.LocalDate]): List[Boolean] = window.dates.map(days.contains)
    List(
      stats
        .hydrationCalendar(tx, accountId, window)
        .toOption
        .map(days => ShareCardView.WaterGoal(marks(days.filter((_, day) => day.isGoalDay).keySet))),
      stats.sleepCalendar(tx, accountId, window).toOption.map(days => ShareCardView.SleepLogged(marks(days.keySet))),
      stats.moodCalendar(tx, accountId, window).toOption.map(days => ShareCardView.MoodLogged(marks(days.keySet)))
    ).flatten.filter(hits(_) > 0)

  private def weekMood(tx: Tx, accountId: UUID, window: DateRange): Option[ShareCardView] =
    stats
      .moodCalendar(tx, accountId, window)
      .toOption
      .filter(_.nonEmpty)
      .map: days =>
        val checkins = days.values.map(_.n).sum
        val energies = days.values.map(_.energyN).sum
        ShareCardView.WeekMood(
          days.values.map(_.valenceSum).sum.toDouble / checkins,
          Option.when(energies > 0)(days.values.map(_.energySum).sum.toDouble / energies)
        )

object ShareFacts:

  private def hits(fact: ShareCardView): Int = fact match
    case ShareCardView.WaterGoal(days)   => days.count(identity)
    case ShareCardView.SleepLogged(days) => days.count(identity)
    case ShareCardView.MoodLogged(days)  => days.count(identity)
    case ShareCardView.WeekMood(_, _)    => 1

  def codeOf(fact: ShareCardView): Int = fact match
    case ShareCardView.WaterGoal(_)   => 1
    case ShareCardView.SleepLogged(_) => 2
    case ShareCardView.MoodLogged(_)  => 3
    case ShareCardView.WeekMood(_, _) => 4

  /** The card's own sentence: the user sees what the card says before it is sent (VA-IMG-17b). */
  def label(fact: ShareCardView): String = fact match
    case ShareCardView.WaterGoal(days)      => ShareCopy.waterGoal(days.count(identity))
    case ShareCardView.SleepLogged(days)    => ShareCopy.sleepLogged(days.count(identity))
    case ShareCardView.MoodLogged(days)     => ShareCopy.moodLogged(days.count(identity))
    case ShareCardView.WeekMood(valence, _) => ShareCopy.weekMood(MoodLevel.ofMean(valence))
