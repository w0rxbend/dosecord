package dosegram.app

import dosegram.api.Charts
import dosegram.core.domain.copy.*
import dosegram.core.domain.input.TimeOfDay
import dosegram.core.domain.insights.BaselineMetric
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.LoggingDomain
import dosegram.core.domain.insights.LoggingMilestone
import dosegram.core.domain.reminders.PromptKind
import dosegram.render.card.DayData
import dosegram.render.card.DoseCounts
import dosegram.render.chart.DoseStatus
import dosegram.render.copy.UkrainianImageCopy

import java.time.LocalDate

/** Leftover 3 of 2026-11-28: every Ukrainian phrase that puts a number before a noun, rendered with 0, 1, 2, 5, 11 and
  * 21, in the chat catalogue, the image catalogue and the API's words. A noun after a bare number takes the form of its
  * plural category (1 день, 2 дні, 5 днів, 21 день); after "з", "із", "до", "від" or "понад" it takes the genitive (з 1
  * дня, з 2 днів, з 21 дня). The check knows the nouns of the catalogues; a word it does not know is not checked, so a
  * new counted noun is added to `Nouns` with its phrase.
  */
class UkrainianNumbersSuite extends munit.FunSuite:

  private val Counts = List(0, 1, 2, 5, 11, 21)
  private val Day = LocalDate.of(2026, 11, 16)

  /** A counted noun: the nominative after a bare number (one, few, many) and the genitive singular after "з". The
    * genitive plural is the "many" form.
    */
  final private case class Noun(one: String, few: String, many: String, ofOne: String):
    def forms: List[String] = List(one, few, many, ofOne)
    def after(n: Int, governed: Boolean): String =
      val category = Plural.category(Lang.Uk, n.toLong)
      if governed then (if category == PluralCategory.One then ofOne else many)
      else
        category match
          case PluralCategory.One => one
          case PluralCategory.Few => few
          case _                  => many

  private val Nouns = List(
    Noun("день", "дні", "днів", "дня"),
    Noun("ніч", "ночі", "ночей", "ночі"),
    Noun("тиждень", "тижні", "тижнів", "тижня"),
    Noun("місяць", "місяці", "місяців", "місяця"),
    Noun("доза", "дози", "доз", "дози"),
    Noun("запланована доза", "заплановані дози", "запланованих доз", "запланованої дози"),
    Noun("прийнята доза", "прийняті дози", "прийнятих доз", "прийнятої дози"),
    Noun("доза з відповіддю", "дози з відповіддю", "доз із відповіддю", "дози з відповіддю"),
    Noun("прийом їжі", "прийоми їжі", "прийомів їжі", "прийому їжі"),
    Noun("запланований прийом їжі", "заплановані прийоми їжі", "запланованих прийомів їжі", "запланованого прийому їжі"),
    Noun("перекус", "перекуси", "перекусів", "перекусу"),
    Noun("позначка", "позначки", "позначок", "позначки"),
    Noun("запис", "записи", "записів", "запису"),
    Noun("розклад", "розклади", "розкладів", "розкладу"),
    Noun("раз", "рази", "разів", "разу"),
    Noun("рівень", "рівні", "рівнів", "рівня"),
    Noun("символ", "символи", "символів", "символу")
  )

  private val Governing = Set("з", "із", "до", "від", "понад")

  // A whole number that is not part of a time, a date with dots, a decimal or a larger number.
  private val Number = """(?<![\d:.,])(\d+)(?![\d]|[:.,]\d)""".r

  /** Every counted noun after a number in `text` that has the wrong form, as "1 днів (expected день)". */
  private def disagreements(text: String): List[String] =
    Number
      .findAllMatchIn(text)
      .flatMap { found =>
        val n = found.group(1).toInt
        val before = text.take(found.start).trim.split("""[\s(«]+""").lastOption.getOrElse("")
        val after = text.drop(found.end)
        val matched = for
          noun <- Nouns
          form <- noun.forms
          if after.startsWith(s" $form") && !after.drop(form.length + 1).headOption.exists(_.isLetter)
        yield (noun, form)
        matched.maxByOption(_._2.length).flatMap { (noun, form) =>
          val expected = noun.after(n, Governing(before))
          Option.when(form != expected)(s"${text.slice(found.start, found.end)} $form (expected $expected)")
        }
      }
      .toList

  private def check(name: String, phrase: Int => String): Unit =
    Counts.foreach { n =>
      val text = Lang.within(Lang.Uk)(phrase(n))
      assertEquals(disagreements(text), Nil, s"$name with $n: $text")
    }

  test("the check itself: a wrong form is found, a right one is not"):
    assertEquals(disagreements("за 1 днів"), List("1 днів (expected день)"))
    assertEquals(disagreements("з 2 дня"), List("2 дня (expected днів)"))
    assertEquals(disagreements("1 з 1 запланованих доз"), List("1 запланованих доз (expected запланованої дози)"))
    assertEquals(disagreements("за 21 день, з 21 дня, о 09:00, 5 днів, з 11 днів"), Nil)

  private val chatPhrases: List[(String, Int => String)] = List(
    "export.taken" -> (n => ExportCopy.taken(n, n, Some(50))),
    "export.taken.few" -> (n => ExportCopy.taken(n, n, None)),
    "export.as_needed" -> (n => ExportCopy.asNeeded(n)),
    "export.goal_days" -> (n => ExportCopy.goalDays(n, n)),
    "image.daily_limit" -> (n => ImageCopy.dailyLimit(n, "14:05")),
    "digest.header" -> (n => DigestCopy.header(n)),
    "digest.more" -> (n => DigestCopy.more(n)),
    "food.too_long" -> (n => FoodCopy.tooLong(n)),
    "month.days_with_records" -> (n => MonthCopy.daysWithRecords(n)),
    "med.every_n_days" -> (n => MedicationDepthCopy.everyNDays(n, Day)),
    "med.cycle" -> (n => MedicationDepthCopy.cycle(n, n, Day)),
    "med.every_n_weeks" -> (n => MedicationDepthCopy.everyNWeeks(n, List("пн"), Day)),
    "med.interval" -> (n => MedicationDepthCopy.interval(60 * (n + 1), "06:00", n)),
    "med.prn_facts" -> (n => MedicationDepthCopy.prnFacts(Some(n), Some(60 * (n + 1)), n)),
    "med.pause_days" -> (n => MedicationDepthCopy.pauseDaysLabel(n)),
    "med.threshold_days" -> (n => MedicationDepthCopy.thresholdDays(n)),
    "menu.today_more" -> (n => MenuCopy.todayMore(n)),
    "menu.policy_line" -> (n => MenuCopy.settingsPolicyLine("Vitamin D", "у час прийому", "10 хв", n)),
    "menu.offset_before" -> (n => MenuCopy.policyOffsetBefore(n)),
    "menu.offset_after" -> (n => MenuCopy.policyOffsetAfter(n)),
    "sleep.rated" -> (n => SleepCopy.rated(n)),
    "water.goal_pending" -> (n => WaterCopy.goalPending(n)),
    "water.daily_limit" -> (n => WaterCopy.dailyLimit(n)),
    "insight.baseline.sleep" -> (n => InsightCopy.baseline(BaselineMetric.SleepMinutes, 420, 400, n)),
    "insight.baseline.water" -> (n => InsightCopy.baseline(BaselineMetric.WaterMillilitres, 1800, 1500, n)),
    "insight.baseline.mood" -> (n => InsightCopy.baseline(BaselineMetric.MoodLevel, 3.4, 3.0, n)),
    "statscard.meds.period" -> (n => StatsCardCopy.medsPeriod(n)),
    "statscard.meds.title" -> (n => StatsCardCopy.medsTitle(n, Day)),
    "statscard.meds.row" -> (n => StatsCardCopy.medsRow(1, "Vitamin D", StatsCardCopy.MedsTally(n, n, n, n, n))),
    "statscard.meds.as_needed" -> (n => StatsCardCopy.medsAsNeededRow(1, "Ibuprofen", n, n)),
    "share.water_goal" -> (n => ShareCopy.waterGoal(n)),
    "share.sleep_logged" -> (n => ShareCopy.sleepLogged(n)),
    "share.mood_logged" -> (n => ShareCopy.moodLogged(n)),
    "label.snooze" -> (n => Labels.snooze(n)),
    "prompt.medication_line" -> (n => PromptCopy.medicationLine(n)),
    "prompt.pause" -> (n => PromptCopy.Pause(n)),
    "reminder.repeat" -> (n => ReminderCopy.repeatLine("13:00", n, n)),
    "report.offer.week" -> (n => ReportCopy.offer(PromptKind.WeeklyReport, n)),
    "report.offer.month" -> (n => ReportCopy.offer(PromptKind.MonthlyReport, n)),
    "input.time.too_many" -> (n => InputCopy.time(TimeOfDay.Rejection.TooMany(n))),
    "gamification.milestone.water" -> (n => GamificationCopy.milestone(LoggingMilestone(LoggingDomain.Water, n))),
    "gamification.milestone.mood" -> (n => GamificationCopy.milestone(LoggingMilestone(LoggingDomain.Mood, n))),
    "gamification.milestone.sleep" -> (n => GamificationCopy.milestone(LoggingMilestone(LoggingDomain.Sleep, n))),
    "gamification.milestone.food" -> (n => GamificationCopy.milestone(LoggingMilestone(LoggingDomain.Food, n))),
    "stats.dose_figures" -> (n => StatsCopy.doseFigures(figures(n)).mkString(" · "))
  )

  /** Dose figures whose every bucket holds `n`, with the rates below their minimum sample. */
  private def figures(n: Int): dosegram.core.domain.insights.MedicationFigures =
    val window = dosegram.core.domain.insights.DateRange.endingOn(Day, 7)
    val insufficient = dosegram.core.domain.insights.Stat.Insufficient(n, 1000, window)
    dosegram.core.domain.insights.MedicationFigures(DoseBuckets(n, n, n, n, n, n, n), insufficient, insufficient, insufficient)

  chatPhrases.foreach((name, phrase) => test(s"chat: $name agrees with its number")(check(name, phrase)))

  private def statuses(n: Int): Vector[DoseStatus] =
    Vector.fill(n)(DoseStatus.TakenOnTime) ++ Vector.fill(n)(DoseStatus.Skipped) ++ Vector.fill(n)(DoseStatus.Open)

  private val imagePhrases: List[(String, Int => String)] = List(
    "taken_of" -> (n => UkrainianImageCopy.takenOf(n, n)),
    "meals_of" -> (n => UkrainianImageCopy.mealsOf(n, n)),
    "snack_count" -> (n => UkrainianImageCopy.snackCount(n)),
    "entries" -> (n => UkrainianImageCopy.entries(n)),
    "resolved_doses_taken" -> (n => UkrainianImageCopy.resolvedDosesTaken(n, n)),
    "unknown_doses_not_counted" -> (n => UkrainianImageCopy.unknownDosesNotCounted(n)),
    "page_of" -> (n => UkrainianImageCopy.pageOf(n, n)),
    "logs_on_days" -> (n => UkrainianImageCopy.logsOnDays(n, n)),
    "meals_logged" -> (n => UkrainianImageCopy.mealsLogged(n)),
    "check_in_days" -> (n => UkrainianImageCopy.checkInDays(n)),
    "sleep_average_of" -> (n => UkrainianImageCopy.sleepAverageOf(420, n)),
    "month_hero_days" -> (n => UkrainianImageCopy.monthHeroDays(n).mkString(" ")),
    "month_hero_doses" -> (n => UkrainianImageCopy.monthHeroDoses(DoseCounts(n, 0, 0, 0, 0, 0)).mkString(" ")),
    "insight_days" -> (n => UkrainianImageCopy.insightDays(n)),
    "mood_calendar_fact" -> (n => UkrainianImageCopy.moodCalendarFact(n)),
    "mood_timeline_fact" -> (n => UkrainianImageCopy.moodTimelineFact(n, n)),
    "sleep_nights" -> (n => UkrainianImageCopy.sleepNights(n)),
    "average_a_day" -> (n => UkrainianImageCopy.averageADay(n)),
    "days_logged" -> (n => UkrainianImageCopy.daysLogged(n)),
    "average_over_nights" -> (n => UkrainianImageCopy.averageOverNights(n)),
    "nights_logged" -> (n => UkrainianImageCopy.nightsLogged(n)),
    "mood_weekday_days" -> (n => UkrainianImageCopy.moodWeekdayDays(n)),
    "poster_fact" -> (n => UkrainianImageCopy.posterFact(n, n)),
    "week_doses" -> (n => UkrainianImageCopy.weekDoses(DoseCounts.of(statuses(n)))),
    "strip_figures" -> (n => UkrainianImageCopy.stripFigures(DoseCounts.of(statuses(n))).mkString(", ")),
    "day_text" -> (n => UkrainianImageCopy.dayText(DayData(Day, None, Some(statuses(n)), None, None, None))),
    "goal_reached_days" -> (n => UkrainianImageCopy.goalReachedDays(dosegram.render.copy.GoalDays(n, n)))
  )

  imagePhrases.foreach((name, phrase) => test(s"image: $name agrees with its number")(check(name, phrase)))

  test("api: the doses of a day agree with their numbers"):
    check("charts.doses_of", n => Charts.dosesOf(DoseBuckets(n, n, n, n, n, n, 0)))
end UkrainianNumbersSuite
