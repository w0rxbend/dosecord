package dosegram.render

import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.*
import dosegram.render.chart.DoseStatus
import dosegram.render.chart.DoseStatus.*
import dosegram.render.copy.EnglishImageCopy
import dosegram.render.text.TableTextMeasurer
import dosegram.render.text.TextFitter
import dosegram.render.tokens.Theme

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** One synthetic view model and the card it renders to. No value here comes from a real account. */
final case class Fixture(name: String, render: CardContext => Vector[CardOutput])

/** The synthetic fixtures behind the goldens, the lints, the pixel checks and the contact sheet: empty, typical and
  * extreme data for every card.
  */
object Samples:
  val Fitter: TextFitter = TextFitter(TableTextMeasurer)

  /** A name no golden at a discreet or aggregate level may contain (VA-PV-01). */
  val CanaryName = "Zyqoxanil-Canary 500 mg"

  /** Markup, quotes, control and bidirectional characters, emoji, right-to-left text and a long tail. */
  val HostileName =
    "<script>alert(\"x\")</script> & 'Ωmega' ‮evil⁦ \u0007 🚀 שלום مرحبا — a name that goes on and on and on and on"

  def context(theme: Theme, privacy: Privacy): CardContext = CardContext(theme, privacy, EnglishImageCopy, Fitter)

  private val Today = LocalDate.of(2026, 11, 12)

  private def reading(valence: Int, energy: Int): MoodReading = MoodReading(MoodState(valence, energy), isEnergyGiven = true)

  val moods: Vector[Fixture] = Vector(
    Fixture("mood/very-unpleasant-very-high", context => Vector(MoodCard.render(Some(reading(1, 5)), context))),
    Fixture("mood/unpleasant-low", context => Vector(MoodCard.render(Some(reading(2, 2)), context))),
    Fixture(
      "mood/neutral-no-energy",
      context => Vector(MoodCard.render(Some(MoodReading(MoodState.captured(3, None), isEnergyGiven = false)), context))
    ),
    Fixture("mood/pleasant-high", context => Vector(MoodCard.render(Some(reading(6, 4)), context))),
    Fixture("mood/very-pleasant-very-high", context => Vector(MoodCard.render(Some(reading(7, 5)), context))),
    Fixture("mood/no-entry", context => Vector(MoodCard.render(None, context)))
  )

  val partialDay: DayData = DayData(
    Today,
    Some(reading(6, 4)),
    Some(Vector(TakenOnTime, TakenLate, Skipped, Open)),
    Some(WaterProgress(1400, 2000)),
    Some(MealProgress(2, 3)),
    Some(SleepEvidence.Recorded(430, 480))
  )

  val days: Vector[Fixture] = Vector(
    Fixture("day/partial", context => Vector(DayCard.render(partialDay, context))),
    Fixture(
      "day/empty",
      context =>
        val day = DayData(
          Today,
          None,
          Some(Vector(Open, Open, Open)),
          Some(WaterProgress(0, 2000)),
          Some(MealProgress(0, 3)),
          Some(SleepEvidence.NoRecord)
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture(
      "day/full",
      context =>
        val day = DayData(
          Today,
          Some(reading(7, 3)),
          Some(Vector(TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime)),
          Some(WaterProgress(2600, 2000)),
          Some(MealProgress(4, 3)),
          Some(SleepEvidence.Recorded(505, 480))
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture(
      "day/missed-doses-same-mood",
      context => Vector(DayCard.render(partialDay.copy(medication = Some(Vector(Missed, Missed, Skipped, Unknown))), context))
    ),
    Fixture(
      "day/three-domains-estimate",
      context =>
        val day = DayData(
          Today,
          Some(reading(2, 2)),
          Some(Vector(TakenOnTime, Missed)),
          Some(WaterProgress(600, 2500)),
          None,
          Some(SleepEvidence.QuietWindowOnly)
        )
        Vector(DayCard.render(day, context))
    ),
    Fixture("day/mood-only", context => Vector(DayCard.render(DayData(Today, Some(reading(4, 3)), None, None, None, None), context)))
  )

  private def water(volumeMl: Int, entryCount: Int, lastEntry: Option[LocalTime]): WaterData =
    WaterData(Today, WaterProgress(volumeMl, 2000), entryCount, lastEntry, usesDrinkFactors = false)

  val waters: Vector[Fixture] = Vector(
    Fixture("water/empty", context => Vector(WaterCard.render(water(0, 0, None), context))),
    Fixture("water/partial", context => Vector(WaterCard.render(water(1400, 5, Some(LocalTime.of(15, 20))), context))),
    Fixture("water/first-glass", context => Vector(WaterCard.render(water(150, 1, Some(LocalTime.of(8, 5))), context))),
    Fixture("water/goal-reached", context => Vector(WaterCard.render(water(2000, 8, Some(LocalTime.of(19, 40))), context))),
    Fixture(
      "water/far-above-goal",
      context => Vector(WaterCard.render(water(4750, 14, Some(LocalTime.of(22, 10))).copy(usesDrinkFactors = true), context))
    )
  )

  private def resolved(taken: Int, resolved: Int): DayAdherence = DayAdherence.Resolved(taken, resolved, hasMissed = taken < resolved)

  // October 2026: mostly complete days, with one of every other class, and the last days still to come.
  private val octoberDays: Vector[DayAdherence] =
    Vector.tabulate(31) {
      case 2            => resolved(3, 4)
      case 6            => resolved(2, 4)
      case 10           => DayAdherence.Resolved(0, 4, hasMissed = false)
      case 12           => DayAdherence.NotKnown
      case 15           => DayAdherence.NothingScheduled
      case 19           => resolved(1, 4)
      case 23           => resolved(0, 4)
      case day if day >= 28 => DayAdherence.Open
      case _            => resolved(4, 4)
    }

  private val october = CalendarData(YearMonth.of(2026, 10), DayOfWeek.MONDAY, Some(LocalDate.of(2026, 10, 28)), None, octoberDays, 2)

  val calendars: Vector[Fixture] = Vector(
    Fixture("calendar/typical", context => Vector(AdherenceCalendarCard.render(october, context))),
    Fixture(
      "calendar/empty",
      context =>
        val empty = CalendarData(YearMonth.of(2026, 2), DayOfWeek.MONDAY, None, None, Vector.fill(28)(DayAdherence.NothingScheduled), 0)
        Vector(AdherenceCalendarCard.render(empty, context))
    ),
    Fixture(
      "calendar/six-weeks-one-medication",
      context =>
        val august = october.copy(month = YearMonth.of(2026, 8), today = None, medicationName = Some(CanaryName))
        Vector(AdherenceCalendarCard.render(august, context))
    )
  )

  private def everyDay(dayCount: Int, statuses: DoseStatus*): Vector[Vector[DoseStatus]] = Vector.fill(dayCount)(statuses.toVector)

  private val fortnight = StripData(
    LocalDate.of(2026, 10, 27),
    14,
    Vector(
      MedicationRow("Metformin 500 mg", isAsNeeded = false, everyDay(14, TakenOnTime, TakenOnTime).updated(8, Vector(TakenOnTime, Skipped)).updated(13, Vector(TakenLate, Open))),
      MedicationRow("Vitamin D", isAsNeeded = false, Vector.tabulate(14)(day => if day % 2 == 0 then Vector(TakenOnTime) else Vector.empty)),
      MedicationRow("Ibuprofen", isAsNeeded = true, Vector.tabulate(14)(day => if Set(1, 4, 9)(day) then Vector(AsNeeded) else Vector.empty))
    )
  )

  private val manyMedications = StripData(
    LocalDate.of(2026, 11, 2),
    7,
    Vector(
      MedicationRow(CanaryName, isAsNeeded = false, everyDay(7, TakenOnTime, TakenLate, Missed)),
      MedicationRow(HostileName, isAsNeeded = false, everyDay(7, TakenOnTime, Skipped, TakenOnTime, Unknown, TakenOnTime, Missed))
    ) ++ (3 to 9).map(index => MedicationRow(s"Medication number $index", isAsNeeded = index == 9, everyDay(7, if index == 9 then AsNeeded else TakenOnTime)))
  )

  val strips: Vector[Fixture] = Vector(
    Fixture("strip/fortnight", context => MedicationStripCard.render(fortnight, context)),
    Fixture("strip/paginated-hostile", context => MedicationStripCard.render(manyMedications, context)),
    Fixture("strip/empty", context => MedicationStripCard.render(StripData(LocalDate.of(2026, 11, 2), 7, Vector.empty), context))
  )

  private val typicalWeek = WeekData(
    LocalDate.of(2026, 11, 2),
    Some(everyDay(7, TakenOnTime, TakenOnTime, TakenOnTime, TakenOnTime).updated(2, Vector(TakenOnTime, TakenLate, Skipped, TakenOnTime)).updated(5, Vector(TakenOnTime, Missed, TakenOnTime, TakenOnTime))),
    Some(Vector(2100, 1900, 2400, 0, 2000, 1200, 2050).map(volume => Option.when(volume > 0)(WaterProgress(volume, 2000)))),
    Some(Vector(MealDay(3, 1), MealDay(3, 0), MealDay(2, 1), MealDay(3, 0), MealDay(3, 2), MealDay(2, 0), MealDay(3, 0)).map(Some(_))),
    Some(Vector(Some(MoodState(6, 4)), Some(MoodState(4, 3)), Some(MoodState(2, 2)), None, Some(MoodState(6, 3)), Some(MoodState(7, 5)), Some(MoodState(4, 1)))),
    Some(Vector(430, 455, 380, 0, 440, 510, -1).map { minutes =>
      if minutes > 0 then SleepEvidence.Recorded(minutes, 480) else if minutes == 0 then SleepEvidence.NoRecord else SleepEvidence.QuietWindowOnly
    })
  )

  val weeks: Vector[Fixture] = Vector(
    Fixture("week/typical", context => Vector(WeekCard.render(typicalWeek, context))),
    Fixture(
      "week/sparse",
      context =>
        val sparse = WeekData(
          typicalWeek.from,
          Some(everyDay(7, TakenOnTime).updated(3, Vector(TakenOnTime, TakenOnTime, TakenOnTime, Skipped, Missed, TakenLate))),
          Some(Vector.fill(7)(None)),
          None,
          Some(Vector.fill(7)(None).updated(1, Some(MoodState(3, 3)))),
          Some(Vector.fill(7)(SleepEvidence.QuietWindowOnly))
        )
        Vector(WeekCard.render(sparse, context))
    ),
    Fixture("week/medication-only", context => Vector(WeekCard.render(WeekData(typicalWeek.from, typicalWeek.medication, None, None, None, None), context)))
  )

  val milestones: Vector[Fixture] = Vector(
    Fixture("milestone/10-water", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.WaterEntries, 10), context))),
    Fixture("milestone/50-mood", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.MoodCheckIns, 50), context))),
    Fixture("milestone/100-meals", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.MealsLogged, 100), context))),
    Fixture("milestone/first-year", context => Vector(MilestoneCard.render(MilestoneData(MilestoneKind.FirstYear, 1), context)))
  )

  val all: Vector[Fixture] = moods ++ days ++ waters ++ calendars ++ strips ++ weeks ++ milestones
