package dosegram.core.domain.copy

import dosegram.core.domain.AdherenceInput
import dosegram.core.domain.OccurrenceStatus
import dosegram.core.domain.insights.ClockSpread
import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.DayPart
import dosegram.core.domain.insights.DayRhythm
import dosegram.core.domain.insights.DoseBuckets
import dosegram.core.domain.insights.DoseTiming
import dosegram.core.domain.insights.HourBand
import dosegram.core.domain.insights.MealTime
import dosegram.core.domain.insights.MedicationDay
import dosegram.core.domain.insights.RecordedDays
import dosegram.core.domain.insights.RhythmDetail
import dosegram.core.domain.insights.RhythmFigures
import dosegram.core.domain.insights.RhythmPattern
import dosegram.core.domain.insights.RhythmRow
import dosegram.core.domain.insights.RhythmSources
import dosegram.core.domain.insights.Stat
import dosegram.core.domain.insights.TrendDomain
import dosegram.core.domain.insights.YearSources
import dosegram.core.domain.nutrition.MealType

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The words of `/stats rhythm`, its card and `GET /insights/rhythm` (supervisor decision, 2026-11-28): when in the
  * local day the user's own records fall, per shown area, over the last 30 days. Each line states counts and clock
  * times of the user's records; nothing names a target time, judges a time or ranks an area. Below the 14-day gate an
  * area shows its recorded days while the counts are kept, else a dash (R-GAM-5).
  */
object RhythmCopy:

  import StatsCopy.duration

  private val NoRecord = "—"

  def header(zone: ZoneId): String =
    tr(s"Rhythm of the day · last 30 days · ${zone.getId}", s"Ритм дня · останні 30 днів · ${zone.getId}")

  /** "30 Oct to 28 Nov: days with records 21 of 30", with the outage days after it when there are any. */
  def periodLine(window: DateRange, days: RecordedDays): String =
    val count = tr(
      s"days with records ${days.withRecords} of ${window.days}",
      s"днів із записами ${days.withRecords} з ${window.days}"
    )
    val outage = Option.when(days.outageOnly > 0)(StatsCopy.outageDaysNotCounted(days.outageOnly))
    (s"${CompareCopy.dates(window)}: $count" :: outage.toList).mkString(" · ")

  /** What an area needs, once, while the counts are kept. */
  def gateNote: String =
    val required = DayRhythm.Gate.required
    tr(
      s"An area shows its rhythm once it has records on $required of the ${DayRhythm.WindowDays} days; " +
        "fewer show those days.",
      s"Сфера показує ритм, коли записи є щонайменше за $required з ${UkNouns.DaysOf.count(DayRhythm.WindowDays)}; " +
        "якщо менше, показано дні із записами."
    )

  def clockNote: String =
    tr("Times are the local clock times of your records.", "Час — місцевий час ваших записів.")

  def none: String = tr("Nothing was recorded in the last 30 days.", "За останні 30 днів нічого не записано.")

  /** The closing sentence: a description of the user's own records, with no target and no cause. */
  def closing: String = tr(
    "These are the times of your own records. They set no target and show no cause.",
    "Це час ваших власних записів. Він не задає цілі й не показує причину."
  )

  def areaName(domain: TrendDomain): String =
    domain match
      case TrendDomain.Medication => tr("Doses", "Дози")
      case TrendDomain.Hydration  => tr("Water", "Вода")
      case TrendDomain.Mood       => tr("Mood check-ins", "Позначки настрою")
      case TrendDomain.Sleep      => tr("Sleep", "Сон")
      case TrendDomain.Nutrition  => tr("Meals", "Прийоми їжі")

  /** What an area's band counts in each hour: a table's column head. */
  def bandName(domain: TrendDomain): String =
    domain match
      case TrendDomain.Medication => tr("Doses taken", "Прийняті дози")
      case TrendDomain.Hydration  => tr("Water entries", "Записи води")
      case TrendDomain.Mood       => tr("Mood check-ins", "Позначки настрою")
      case TrendDomain.Sleep      => tr("Nights asleep", "Ночі уві сні")
      case TrendDomain.Nutrition  => tr("Meals and snacks", "Прийоми їжі й перекуси")

  /** The recorded days of an area: "20 days", "18 nights". */
  def recordedDays(domain: TrendDomain, days: Int): String = CompareCopy.recordedDays(domain, days)

  /** A clock time from minutes after midnight: "07:05". */
  def clock(minute: Int): String = f"${minute / 60}%02d:${minute % 60}%02d"

  /** One hour of the band: "08:00 to 09:00", the last "23:00 to 00:00". */
  def hour(hour: Int): String =
    val from = clock(hour * 60)
    val to = clock((hour + 1) % HourBand.Hours * 60)
    tr(s"$from to $to", s"$from–$to")

  def part(part: DayPart): String =
    part match
      case DayPart.Morning   => tr("morning", "ранок")
      case DayPart.Afternoon => tr("afternoon", "день")
      case DayPart.Evening   => tr("evening", "вечір")
      case DayPart.Night     => tr("night", "ніч")

  /** The records of each quarter of the day: "morning 20, afternoon 4, evening 6, night 0". */
  def parts(band: HourBand): String = band.byPart.map((name, n) => s"${part(name)} $n").mkString(", ")

  /** "most often 08:00 to 09:00", the hour with the most records. */
  def peak(hour: Int): String = tr(s"most often ${this.hour(hour)}", s"найчастіше ${this.hour(hour)}")

  /** The timing of the taken doses against their scheduled time, in plain minutes. */
  def timing(value: DoseTiming): String =
    val median =
      if value.medianMinutes > 0 then
        tr(
          s"a median of ${duration(value.medianMinutes)} after the scheduled time",
          s"медіана — через ${duration(value.medianMinutes)} після запланованого часу"
        )
      else if value.medianMinutes < 0 then
        tr(
          s"a median of ${duration(-value.medianMinutes)} before the scheduled time",
          s"медіана — за ${duration(-value.medianMinutes)} до запланованого часу"
        )
      else tr("a median at the scheduled time", "медіана — у запланований час")
    val within =
      if value.withinMinutes == 0 then tr("9 in 10 at the scheduled time", "9 з 10 — у запланований час")
      else
        tr(
          s"9 in 10 within ${duration(value.withinMinutes)} of it",
          s"9 з 10 — у межах ${duration(value.withinMinutes)} від нього"
        )
    s"$median, $within"

  def spread(name: String, value: ClockSpread): String =
    tr(
      s"$name ${clock(value.typicalMinute)}, varied by about ${duration(value.spreadMinutes)}",
      s"$name ${clock(value.typicalMinute)}, різнився приблизно на ${duration(value.spreadMinutes)}"
    )

  def bedtime(value: ClockSpread): String = spread(tr("usual bedtime", "звичний відхід до сну"), value)
  def wake(value: ClockSpread): String = spread(tr("usual wake time", "звичний підйом"), value)

  /** "breakfast around 08:30 (14)". */
  def mealTime(value: MealTime): String =
    val name = FoodCopy.label(value.mealType).toLowerCase(java.util.Locale.ROOT)
    tr(
      s"$name around ${clock(value.typicalMinute)} (${value.entries})",
      s"$name близько ${clock(value.typicalMinute)} (${value.entries})"
    )

  /** What the pattern of an area says, without its name: the line's body and the card's summary under the band. */
  def summary(pattern: RhythmPattern): String =
    val band = pattern.band
    def withPeak(total: String): String =
      (List(total) ++ band.peak.map(peak).toList).mkString(", ") +
        (if band.total > 0 then s" · ${parts(band)}" else "")
    pattern.detail match
      case RhythmDetail.Entries =>
        withPeak(counted(band.total, "entry", "entries", UkNouns.Entries))
      case RhythmDetail.CheckIns =>
        withPeak(counted(band.total, "check-in", "check-ins", UkNouns.CheckIns))
      case RhythmDetail.Doses(timing) =>
        if band.total == 0 then tr("no dose recorded as taken", "немає доз, записаних як прийняті")
        else
          val taken = withPeak(tr(s"${band.total} taken", s"прийнято ${band.total}"))
          (taken :: timing.shown.map(this.timing).toList).mkString(" · ")
      case RhythmDetail.Nights(bedtime, wake) => s"${this.bedtime(bedtime)} · ${this.wake(wake)}"
      case RhythmDetail.Meals(types)          =>
        (List(types.map(mealTime).mkString(", ")) ++ band.peak.map(peak).toList).mkString(" · ")

  /** The short line under an area's band on the card: the busiest hour, or the usual bedtime and wake time. */
  def cardSummary(pattern: RhythmPattern): String =
    pattern.detail match
      case RhythmDetail.Nights(bedtime, wake) =>
        tr(
          s"usually: bedtime ${clock(bedtime.typicalMinute)}, wake ${clock(wake.typicalMinute)}",
          s"звично: сон ${clock(bedtime.typicalMinute)}, підйом ${clock(wake.typicalMinute)}"
        )
      case RhythmDetail.Doses(_) if pattern.band.total == 0 =>
        tr("no dose recorded as taken", "немає доз, записаних як прийняті")
      case _ => pattern.band.peak.fold(NoRecord)(peak)

  /** An area below the 14-day gate on the card: its recorded days, or that it has none. */
  def belowGate(domain: TrendDomain, days: Int): String =
    if days == 0 then tr("no records", "немає записів")
    else tr(s"records on ${recordedDays(domain, days)}", s"записи за ${recordedDays(domain, days)}")

  /** One area: its name, its days while the counts are kept, and its rhythm or, below the gate, its recorded days. */
  def rowLine(row: RhythmRow, keepGates: Boolean): String =
    val name = areaName(row.domain)
    val days = recordedDays(row.domain, row.recordedDays)
    (row.pattern.shown, keepGates) match
      case (Some(pattern), true)                => s"$name ($days): ${summary(pattern)}"
      case (Some(pattern), false)               => s"$name: ${summary(pattern)}"
      case (None, true) if row.recordedDays > 0 => s"$name: ${belowGate(row.domain, row.recordedDays)}"
      case (None, _)                            => s"$name: $NoRecord"

  /** Every line of `/stats rhythm`, the caption of its card and the API's `lines`. */
  def lines(figures: RhythmFigures, zone: ZoneId, keepGates: Boolean): List[String] =
    val head = List(header(zone), periodLine(figures.window, figures.records))
    if figures.rows.isEmpty || !figures.hasRecords then head :+ none
    else
      head ++ (if keepGates then List(gateNote) else Nil) ++ List(clockNote) ++
        figures.rows.map(rowLine(_, keepGates)) :+ closing

  // ---------- buttons ----------

  /** The way to the rhythm from the 30 days (BOT_UX section 12.5); the words are the card's title. */
  def Rhythm: String = tr("Rhythm of the day", "Ритм дня")

  def entries: List[CopyEntry] = Samples.entries

  private object Samples:
    private val today = LocalDate.parse("2026-11-26")
    private val kyiv = ZoneId.of("Europe/Kyiv")
    private val medication = UUID.fromString("00000000-0000-7000-8000-000000000001")
    private val window = DayRhythm.window(today)

    private def at(daysAgo: Int, hour: Int, minute: Int): Instant =
      today.minusDays(daysAgo.toLong).atTime(hour, minute).atZone(kyiv).toInstant

    private val doses = (0 until 20).toList.map: daysAgo =>
      val scheduled = at(daysAgo, 8, 0)
      AdherenceInput(
        scheduledFor = scheduled,
        status = OccurrenceStatus.Taken,
        effectiveAt = Some(scheduled.plusSeconds(60L * (5 + daysAgo % 4 * 10))),
        resolvedAt = Some(scheduled),
        dueWindowEnd = scheduled.plusSeconds(3600),
        manual = false,
        corrected = false
      )

    private val sources = RhythmSources(
      days = YearSources(
        medication = Some(
          (0 until 20)
            .map(daysAgo =>
              today.minusDays(daysAgo.toLong) -> MedicationDay(
                Map(medication -> DoseBuckets.zero.copy(takenOnTime = 1))
              )
            )
            .toMap
        ),
        hydration = Some(Map.empty),
        mood = None,
        sleep = None,
        food = None
      ),
      zone = kyiv,
      doses = doses
    )

    def entries: List[CopyEntry] =
      val figures = DayRhythm.of(window, sources)
      List(
        CopyEntry("stats.rhythm", lines(figures, kyiv, keepGates = true).mkString("\n")),
        CopyEntry("stats.rhythm.omitting_gates", lines(figures, kyiv, keepGates = false).mkString("\n")),
        CopyEntry("stats.rhythm.none", none),
        CopyEntry("stats.rhythm.gate", gateNote),
        CopyEntry("stats.rhythm.clock", clockNote),
        CopyEntry("stats.rhythm.closing", closing),
        CopyEntry("stats.rhythm.button", Rhythm),
        CopyEntry("stats.rhythm.sleep", s"${bedtime(ClockSpread(23 * 60 + 40, 35))} · ${wake(ClockSpread(430, 20))}"),
        CopyEntry(
          "stats.rhythm.meals",
          List(MealTime(MealType.Breakfast, 14, 510), MealTime(MealType.Snack, 6, 960)).map(mealTime).mkString(", ")
        ),
        CopyEntry("stats.rhythm.timing.before", timing(DoseTiming(-4, 25))),
        CopyEntry("stats.rhythm.timing.exact", timing(DoseTiming(0, 0))),
        CopyEntry(
          "stats.rhythm.below_gate",
          rowLine(RhythmRow(TrendDomain.Hydration, 9, Stat.Insufficient(9, 14, window)), keepGates = true)
        ),
        CopyEntry(
          "stats.rhythm.card.sleep",
          cardSummary(RhythmPattern(HourBand.of(Nil), RhythmDetail.Nights(ClockSpread(1420, 30), ClockSpread(430, 20))))
        )
      ) ++ TrendDomain.values.toList.map(domain =>
        CopyEntry(s"stats.rhythm.counts.${domain.toString.toLowerCase(java.util.Locale.ROOT)}", bandName(domain))
      )
  end Samples
end RhythmCopy
