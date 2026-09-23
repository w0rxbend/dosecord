package dosecord.simulation

import dosecord.contracts.HhMm
import dosecord.contracts.Weekday
import dosecord.core.domain.Rule
import dosecord.core.domain.SlotGroup

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import scala.collection.mutable

/** Which recorded console message a scripted tap answers: the newest one whose body/choices match. */
enum Selector:
  case Reminder, PostTaken, MissedNotice, Digest, CorrectionPrompt, MainMenu, AccountMenu, MedicationsMenu,
    TimezonePicker, TimezoneConfirm

  def matches(message: RecordedMessage): Boolean =
    val chunks = message.rendered.chunks
    val labels = message.rendered.choiceMap.map(_.label)
    this match
      case Reminder         => chunks.exists(_.startsWith("Time for ")) && labels.contains("Taken")
      case PostTaken        => chunks.exists(_.startsWith("Recorded at ")) && labels.contains("Undo")
      case MissedNotice     => chunks.exists(_.contains("marked it missed")) && labels.contains("Keep missed")
      case Digest           => chunks.exists(_.contains("While I was away")) && labels.contains("I took it")
      case CorrectionPrompt => chunks.exists(_.contains("When did you take it?")) && labels.contains("Now")
      case MainMenu         => chunks.exists(_.contains("What would you like to do?"))
      case AccountMenu      => chunks.exists(_.contains("Account")) && labels.contains("Timezone")
      case MedicationsMenu  => labels.contains("Add medication")
      case TimezonePicker   => chunks.exists(_.contains("Pick your timezone:"))
      case TimezoneConfirm  => chunks.exists(_.contains("right?")) && labels.contains("Yes")

/** One scripted user/worker action; all user behaviour goes through the real mediator as console wire events. */
enum Step:
  case Tap(persona: String, selector: Selector, label: String)
  case Command(persona: String, raw: String)
  case Text(persona: String, text: String)
  case EditSchedule(persona: String, rule: Rule, note: String)
  case WorkerDown
  case WorkerUp

/** The scripted actions due at one virtual instant, in order. */
final case class Scripted(at: Instant, steps: List[Step])

/** The M1.11 scenario (ROADMAP line 92): 30 virtual days for the four personas available now — daily 09:00 (p1),
  * twice daily (p2), Mon/Wed/Fri (p3), as-needed logged via `/log` (p4) — with late answers, two snoozes, an ignore,
  * an undo, two corrections, zone travel east (UTC -> Africa/Lagos) and west (-> America/Phoenix), pause/resume, a
  * same-day edit, and a 6 h outage on day 12 (2026-10-16 08:00-14:00 UTC).
  *
  * Fire times are staggered by a few minutes per persona so no two occurrences share a `next_action_at` (the claim's
  * tie order is not guaranteed); travel zones are DST-free (Africa/Lagos UTC+1, America/Phoenix UTC-7), so the JVM
  * default tzdata cannot deviate from the pinned expectations inside the window.
  */
object Scenario:

  val T0: Instant = Instant.parse("2026-10-05T00:00:00Z") // a Monday
  val End: Instant = T0.plusSeconds(30 * 24 * 3600L)      // 2026-11-04T00:00:00Z

  val outageStart: Instant = Instant.parse("2026-10-16T08:00:00Z") // day 12
  val outageEnd: Instant = Instant.parse("2026-10-16T14:00:00Z")

  /** Personas the slice registers as pending: they need M7 rule kinds that validation rejects until then. Listed in
    * the harness (and echoed into the stats golden) — deliberately not implemented.
    */
  val pendingPersonas: List[String] = List(
    "every-8-hours interval dosing (Rule.IntervalFixedStart — M7)",
    "3-weeks-on/1-week-off cycle (Rule.Cycle — M7)",
    "antibiotic chain every 6 h after the previous dose (Rule.Chain — M7.2)",
    "steroid taper (Rule.Taper — M7)"
  )

  private val travelEastDay = LocalDate.of(2026, 10, 13)
  private val travelWestDay = LocalDate.of(2026, 10, 25)
  private val editDay = LocalDate.of(2026, 10, 26)
  private val pauseDay = LocalDate.of(2026, 10, 21)
  private val resumeDay = LocalDate.of(2026, 10, 23)

  private def utc(day: LocalDate, hour: Int, minute: Int): Instant =
    day.atTime(hour, minute).toInstant(ZoneOffset.UTC)

  private def weekdayOf(day: LocalDate): Weekday = day.getDayOfWeek match
    case java.time.DayOfWeek.MONDAY    => Weekday.Mon
    case java.time.DayOfWeek.TUESDAY   => Weekday.Tue
    case java.time.DayOfWeek.WEDNESDAY => Weekday.Wed
    case java.time.DayOfWeek.THURSDAY  => Weekday.Thu
    case java.time.DayOfWeek.FRIDAY    => Weekday.Fri
    case java.time.DayOfWeek.SATURDAY  => Weekday.Sat
    case java.time.DayOfWeek.SUNDAY    => Weekday.Sun

  private def daily(times: String*): Rule.FixedTimes =
    Rule.FixedTimes(List(SlotGroup(Weekday.values.toList, times.toList.map(HhMm.unsafe))))

  /** p1's dose fire time (UTC) on a day, following the two travels and the same-day edit. */
  private def p1Fire(day: LocalDate): (Int, Int) =
    if !day.isAfter(travelEastDay) then (9, 0)        // Oct 5-13: 09:00 UTC
    else if !day.isAfter(travelWestDay) then (8, 0)   // Oct 14-25: 09:00 Africa/Lagos (UTC+1)
    else if day == editDay then (16, 0)               // Oct 26: 09:00 America/Phoenix (UTC-7)
    else (16, 30)                                     // Oct 27+: 09:30 America/Phoenix after the edit

  // ---------- Personas ----------

  final case class PersonaSpec(
      personaId: String,
      zone: String,
      medicationName: String,
      rule: Rule,
      doseAmount: Option[BigDecimal],
      doseUnit: Option[String],
      instructions: Option[String]
  )

  val personas: List[PersonaSpec] = List(
    PersonaSpec("p1", "UTC", "Vitamin D", daily("09:00"), Some(BigDecimal(1000)), Some("IU"), Some("with breakfast")),
    PersonaSpec("p2", "UTC", "Metformin", daily("09:07", "21:07"), Some(BigDecimal(500)), Some("mg"), None),
    PersonaSpec(
      "p3",
      "UTC",
      "Iron",
      Rule.FixedTimes(List(SlotGroup(List(Weekday.Mon, Weekday.Wed, Weekday.Fri), List(HhMm.unsafe("09:23"))))),
      Some(BigDecimal(25)),
      Some("mg"),
      None
    ),
    PersonaSpec("p4", "UTC", "Ibuprofen", Rule.AsNeeded(maxPerDay = 4, minGapMinutes = 240), Some(BigDecimal(200)),
      Some("mg"), None)
  )

  // ---------- The script ----------

  def script: List[Scripted] =
    val b = mutable.ListBuffer.empty[Scripted]
    def on(at: Instant)(steps: Step*): Unit = b += Scripted(at, steps.toList)

    val days = (0 until 30).map(T0.atZone(ZoneOffset.UTC).toLocalDate.plusDays(_))

    // p1 — daily 09:00, the traveller
    days.foreach { day =>
      val (fh, fm) = p1Fire(day)
      val fire = utc(day, fh, fm)
      day match
        case d if d == LocalDate.of(2026, 10, 7) => on(fire.plusSeconds(47 * 60))(taken()) // late, inside the grace
        case d if d == LocalDate.of(2026, 10, 8) => // two snoozes, then taken
          on(fire.plusSeconds(2 * 60))(snooze("Snooze 10m"))
          on(fire.plusSeconds(14 * 60))(snooze("Snooze 30m"))
          on(fire.plusSeconds(46 * 60))(taken())
        case d if d == LocalDate.of(2026, 10, 10) => on(fire.plusSeconds(80 * 60))(taken()) // taken_late
        case d if d == LocalDate.of(2026, 10, 11) => // ignore: repeats, missed notice, late "I took it"
          on(utc(day, 18, 0))(Step.Tap("p1", Selector.MissedNotice, "I took it"))
        case d if d == travelEastDay => // dose, then zone travel east through Account -> Timezone
          on(fire.plusSeconds(3 * 60))(taken())
          on(utc(day, 12, 30))(travel("Africa/Lagos")*)
        case d if d == LocalDate.of(2026, 10, 14) => // undo inside the window, then taken on the repeat
          on(fire.plusSeconds(3 * 60))(taken())
          on(fire.plusSeconds(10 * 60))(Step.Tap("p1", Selector.PostTaken, "Undo"))
          on(fire.plusSeconds(22 * 60))(taken())
        case d if d == LocalDate.of(2026, 10, 16) => // the outage day: the digest's "I took it" after the restart
          on(outageEnd.plusSeconds(5 * 60))(Step.Tap("p1", Selector.Digest, "I took it"))
        case d if d == LocalDate.of(2026, 10, 18) => // correction to the scheduled time, past the undo window
          on(fire.plusSeconds(3 * 60))(taken())
          on(fire.plusSeconds(25 * 60))(Step.Tap("p1", Selector.PostTaken, "Correct"))
          on(fire.plusSeconds(26 * 60))(Step.Tap("p1", Selector.CorrectionPrompt, "At scheduled time"))
        case d if d == LocalDate.of(2026, 10, 19) => // correction to now
          on(fire.plusSeconds(3 * 60))(taken())
          on(fire.plusSeconds(20 * 60))(Step.Tap("p1", Selector.PostTaken, "Correct"))
          on(fire.plusSeconds(21 * 60))(Step.Tap("p1", Selector.CorrectionPrompt, "Now"))
        case d if d == pauseDay => // pause from the menu after the dose
          on(fire.plusSeconds(3 * 60))(taken())
          on(utc(day, 12, 0))(pauseResume("Pause")*)
        case d if d == resumeDay => // resume from the menu
          on(utc(day, 12, 0))(pauseResume("Resume")*)
        case d if d == travelWestDay => // dose, then zone travel west
          on(fire.plusSeconds(3 * 60))(taken())
          on(utc(day, 12, 30))(travel("America/Phoenix")*)
        case d if d == editDay => // same-day edit 09:00 -> 09:30 (M3.2 UI absent: the M1.5 data side)
          on(fire.plusSeconds(3 * 60))(taken())
          on(utc(day, 17, 0))(Step.EditSchedule("p1", daily("09:30"),
            "09:00 -> 09:30 America/Phoenix, same-day edit after the dose"))
        case d if d == LocalDate.of(2026, 10, 22) => () // paused: no dose
        case _ => on(fire.plusSeconds(3 * 60))(taken())
    }

    // p2 — twice daily 09:07 / 21:07
    days.foreach { day =>
      val morning = utc(day, 9, 7)
      val evening = utc(day, 21, 7)
      day match
        case d if d == LocalDate.of(2026, 10, 16) => // morning swallowed by the outage: skip from the digest
          on(outageEnd.plusSeconds(6 * 60))(Step.Tap("p2", Selector.Digest, "Skip"))
          on(evening.plusSeconds(4 * 60))(taken("p2"))
        case d if d == LocalDate.of(2026, 10, 17) => // an evening skip
          on(morning.plusSeconds(2 * 60))(taken("p2"))
          on(evening.plusSeconds(5 * 60))(Step.Tap("p2", Selector.Reminder, "Skip"))
        case d if d == LocalDate.of(2026, 10, 29) => // ignore the morning dose, then Keep missed
          on(utc(day, 11, 30))(Step.Tap("p2", Selector.MissedNotice, "Keep missed"))
          on(evening.plusSeconds(4 * 60))(taken("p2"))
        case _ =>
          on(morning.plusSeconds(2 * 60))(taken("p2"))
          on(evening.plusSeconds(4 * 60))(taken("p2"))
    }

    // p3 — Mon/Wed/Fri 09:23
    days
      .filter(d => Set(Weekday.Mon, Weekday.Wed, Weekday.Fri).contains(weekdayOf(d)))
      .foreach { day =>
        val fire = utc(day, 9, 23)
        day match
          case d if d == LocalDate.of(2026, 10, 9)  => on(fire.plusSeconds(4 * 60))(Step.Tap("p3", Selector.Reminder, "Skip"))
          case d if d == LocalDate.of(2026, 10, 16) => () // the outage: stays unknown
          case _ => on(fire.plusSeconds(3 * 60))(taken("p3"))
      }

    // p4 — as-needed, logged via /log
    List(
      LocalDate.of(2026, 10, 7)  -> (14 -> 0),
      LocalDate.of(2026, 10, 14) -> (9 -> 0),
      LocalDate.of(2026, 10, 21) -> (18 -> 30),
      LocalDate.of(2026, 10, 28) -> (12 -> 0)
    ).foreach { (day, hm) => on(utc(day, hm._1, hm._2))(Step.Command("p4", "/log ibuprofen")) }

    // the 6 h outage on day 12
    on(outageStart)(Step.WorkerDown)
    on(outageEnd)(Step.WorkerUp)

    b.toList.sortBy(_.at)
  end script

  private def taken(persona: String = "p1"): Step = Step.Tap(persona, Selector.Reminder, "Taken")
  private def snooze(label: String): Step = Step.Tap("p1", Selector.Reminder, label)

  private def travel(zone: String): List[Step] = List(
    Step.Command("p1", "/menu"),
    Step.Tap("p1", Selector.MainMenu, "Account"),
    Step.Tap("p1", Selector.AccountMenu, "Timezone"),
    Step.Tap("p1", Selector.TimezonePicker, "Other — type your timezone"),
    Step.Text("p1", zone),
    Step.Tap("p1", Selector.TimezoneConfirm, "Yes")
  )

  private def pauseResume(toggle: String): List[Step] = List(
    Step.Command("p1", "/menu"),
    Step.Tap("p1", Selector.MainMenu, "Medications"),
    Step.Tap("p1", Selector.MedicationsMenu, toggle)
  )
end Scenario
