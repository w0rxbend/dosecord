package dosegram.core.domain.copy

import dosegram.core.domain.insights.StatsPeriod

/** The controls of the `/stats` card (BOT_UX section 12.5). The figures themselves are [[StatsCopy]]. */
object StatsCardCopy:

  def ShowAsImage = tr("Show as image", "Показати зображенням")
  def usage = tr(
    "Send /stats, /stats today, /stats 7, /stats 30 or /stats month.",
    "Надішліть /stats, /stats today, /stats 7, /stats 30 або /stats month."
  )
  def imageUnavailable = tr(
    "The image is not available right now. The figures above are complete.",
    "Зображення зараз недоступне. Цифри вище повні."
  )

  def period(period: StatsPeriod): String =
    period match
      case StatsPeriod.Today      => tr("Today", "Сьогодні")
      case StatsPeriod.Last7Days  => tr("7 days", "7 днів")
      case StatsPeriod.Last30Days => tr("30 days", "30 днів")

  def entries: List[CopyEntry] =
    List(
      CopyEntry("statscard.show_as_image", ShowAsImage),
      CopyEntry("statscard.usage", usage),
      CopyEntry("statscard.image_unavailable", imageUnavailable)
    ) ++ StatsPeriod.values.toList.map(value => CopyEntry(s"statscard.period.${value.days}", period(value)))
end StatsCardCopy
