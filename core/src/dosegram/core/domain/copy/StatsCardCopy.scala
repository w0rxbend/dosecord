package dosegram.core.domain.copy

import dosegram.core.domain.insights.StatsPeriod

/** The controls of the `/stats` card (BOT_UX section 12.5). The figures themselves are [[StatsCopy]]. */
object StatsCardCopy:

  val ShowAsImage = "Show as image"
  val usage = "Send /stats, /stats today, /stats 7, /stats 30 or /stats month."
  val imageUnavailable = "The image is not available right now. The figures above are complete."

  def period(period: StatsPeriod): String =
    period match
      case StatsPeriod.Today      => "Today"
      case StatsPeriod.Last7Days  => "7 days"
      case StatsPeriod.Last30Days => "30 days"

  val entries: List[CopyEntry] =
    List(
      CopyEntry("statscard.show_as_image", ShowAsImage),
      CopyEntry("statscard.usage", usage),
      CopyEntry("statscard.image_unavailable", imageUnavailable)
    ) ++ StatsPeriod.values.toList.map(value => CopyEntry(s"statscard.period.${value.days}", period(value)))
end StatsCardCopy
