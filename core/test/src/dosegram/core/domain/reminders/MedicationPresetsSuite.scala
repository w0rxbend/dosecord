package dosegram.core.domain.reminders

import dosegram.contracts.Weekday
import dosegram.core.domain.Rule
import dosegram.core.domain.copy.PromptCopy

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The medication schedule presets (ADR-023) build rules of the existing schedule model from the day profile. */
class MedicationPresetsSuite extends munit.FunSuite:

  private val zone = ZoneId.of("Europe/Kyiv")
  private val today = LocalDate.of(2026, 11, 16) // a Monday
  private val now = Instant.parse("2026-11-16T00:00:00+02:00")
  private val profile = DayProfile.Default
  private val params = MedicationPresetParams()

  private def times(preset: MedicationPreset, p: DayProfile = profile): List[LocalTime] =
    MedicationPresets
      .preview(MedicationPresets.rule(preset, p, params, today), zone, now, limit = 20)
      .filter(_.atZone(zone).toLocalDate == today)
      .map(_.atZone(zone).toLocalTime)

  test("every preset the engine reminds builds a valid rule the schedule model accepts"):
    MedicationPresets.all.foreach: preset =>
      val rule = MedicationPresets.rule(preset, profile, params, today)
      assertEquals(Rule.validate(rule), Nil, preset.id)
      assertEquals(Rule.isSupportedInMvp(rule), preset.materialised, preset.id)

  test("the daily presets take their times from the anchors"):
    assertEquals(times(MedicationPresets.Morning), List(LocalTime.of(7, 0)))
    assertEquals(times(MedicationPresets.Evening), List(LocalTime.of(19, 0)))
    assertEquals(times(MedicationPresets.TwiceDaily), List(LocalTime.of(7, 0), LocalTime.of(23, 0)))
    assertEquals(times(MedicationPresets.WithMeals), List(LocalTime.of(8, 0), LocalTime.of(13, 0), LocalTime.of(19, 0)))
    assertEquals(times(MedicationPresets.Every8Hours), List(LocalTime.of(7, 0), LocalTime.of(15, 0), LocalTime.of(23, 0)))
    assertEquals(times(MedicationPresets.Every12Hours), List(LocalTime.of(7, 0), LocalTime.of(19, 0)))

  test("a later wake time moves the presets that use it"):
    val late = profile.withAnchor(Anchor.Wake, LocalTime.of(9, 30)).toOption.get
    assertEquals(times(MedicationPresets.Morning, late), List(LocalTime.of(9, 30)))
    assertEquals(times(MedicationPresets.Every12Hours, late), List(LocalTime.of(9, 30), LocalTime.of(21, 30)))

  test("weekdays only and once a week fall on their days"):
    val weekdays = MedicationPresets.preview(
      MedicationPresets.rule(MedicationPresets.Weekdays, profile, params, today),
      zone,
      now,
      limit = 10
    )
    assert(weekdays.forall(_.atZone(zone).getDayOfWeek.getValue <= 5))
    val thursday = MedicationPresetParams(weekday = Weekday.Thu, time = Some(LocalTime.of(10, 0)))
    val weekly = MedicationPresets.preview(MedicationPresets.rule(MedicationPresets.Weekly, profile, thursday, today), zone, now, 3)
    assertEquals(weekly.map(_.atZone(zone).getDayOfWeek), List(DayOfWeek.THURSDAY), "one Thursday in the eight days")
    assertEquals(weekly.head.atZone(zone).toLocalTime, LocalTime.of(10, 0))

  test("as needed has no reminder; every other day and cycles are anchored on today"):
    assertEquals(MedicationPresets.preview(MedicationPresets.rule(MedicationPresets.AsNeeded, profile, params, today), zone, now, 5), Nil)
    assertEquals(
      MedicationPresets.rule(MedicationPresets.CycleOnOff, profile, MedicationPresetParams(onDays = 21, offDays = 7), today),
      Rule.Cycle(21, 7, List(dosegram.contracts.HhMm.unsafe("07:00")), today)
    )
    val everyOther =
      MedicationPresets.preview(MedicationPresets.rule(MedicationPresets.EveryOtherDay, profile, params, today), zone, now, 3)
    assertEquals(everyOther.map(_.atZone(zone).toLocalDate), List(16, 18, 20).map(LocalDate.of(2026, 11, _)))
    MedicationPresets.all.foreach(preset => assert(PromptCopy.presetName(preset.id) != preset.id, preset.id))
