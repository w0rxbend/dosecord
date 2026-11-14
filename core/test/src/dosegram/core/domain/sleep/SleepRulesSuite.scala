package dosegram.core.domain.sleep

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** PRODUCT_SPEC R-SLP-2 and R-SLP-12 (bands), and the grammar of BOT_UX section 11.5. */
class SleepRulesSuite extends munit.FunSuite:

  private val zone = ZoneId.of("Europe/Kyiv")
  private val now = Instant.parse("2026-11-12T08:00:00Z")

  private def ending(wake: Instant, length: Duration): SleepInterval =
    SleepInterval(SleepEdge(wake.minus(length), zone, EdgeSource.CheckIn), SleepEdge(wake, zone, EdgeSource.CheckIn))

  private def lasting(length: Duration): SleepInterval = ending(now, length)

  test("a night lasts from 1 to 18 hours"):
    assertEquals(SleepRules.check(SleepKind.Main, lasting(Duration.ofHours(1)), now), Right(()))
    assertEquals(SleepRules.check(SleepKind.Main, lasting(Duration.ofHours(18)), now), Right(()))
    assertEquals(
      SleepRules.check(SleepKind.Main, lasting(Duration.ofMinutes(59)), now),
      Left(SleepInvalid.TooShort(SleepKind.Main, SleepBounds.MainMinimum))
    )
    assertEquals(
      SleepRules.check(SleepKind.Main, lasting(Duration.ofMinutes(18 * 60 + 1)), now),
      Left(SleepInvalid.TooLong(SleepKind.Main, SleepBounds.MainMaximum))
    )

  test("a nap lasts from 5 minutes to 6 hours"):
    assertEquals(SleepRules.check(SleepKind.Nap, lasting(Duration.ofMinutes(5)), now), Right(()))
    assertEquals(SleepRules.check(SleepKind.Nap, lasting(Duration.ofHours(6)), now), Right(()))
    assertEquals(
      SleepRules.check(SleepKind.Nap, lasting(Duration.ofMinutes(4)), now),
      Left(SleepInvalid.TooShort(SleepKind.Nap, SleepBounds.NapMinimum))
    )
    assertEquals(
      SleepRules.check(SleepKind.Nap, lasting(Duration.ofMinutes(361)), now),
      Left(SleepInvalid.TooLong(SleepKind.Nap, SleepBounds.NapMaximum))
    )

  test("wake comes after onset and neither edge is in the future"):
    assertEquals(SleepRules.check(SleepKind.Main, lasting(Duration.ZERO), now), Left(SleepInvalid.WakeNotAfterOnset))
    assertEquals(
      SleepRules.check(SleepKind.Main, ending(now.plusSeconds(60), Duration.ofHours(8)), now),
      Left(SleepInvalid.InFuture)
    )

  test("the confidence bands begin at 0.8, 0.5 and 0.25, and below that nothing is shown"):
    def band(hundredths: Int): ConfidenceBand = Confidence.ofHundredths(hundredths).get.band
    assertEquals(List(100, 80).map(band), List(ConfidenceBand.High, ConfidenceBand.High))
    assertEquals(List(79, 50).map(band), List(ConfidenceBand.Medium, ConfidenceBand.Medium))
    assertEquals(List(49, 25).map(band), List(ConfidenceBand.Low, ConfidenceBand.Low))
    assertEquals(List(24, 0).map(band), List(ConfidenceBand.Hidden, ConfidenceBand.Hidden))
    assertEquals(Confidence.PassiveCap.band, ConfidenceBand.Low)

  test("a computed confidence is rounded down, never up into a band"):
    assertEquals(Confidence.floorOf(0.4999).hundredths, 49)
    assertEquals(Confidence.floorOf(0.2499).band, ConfidenceBand.Hidden)

  private def time(text: String): LocalTime = LocalTime.parse(text)

  List(
    "slept 23:30 7:10" -> SleepText.Night(time("23:30"), time("07:10")),
    "Slept 23:40 to 07:10" -> SleepText.Night(time("23:40"), time("07:10")),
    "sleep 0:15-6:45" -> SleepText.Night(time("00:15"), time("06:45")),
    "slept 7h" -> SleepText.SleptFor(Duration.ofHours(7)),
    "slept 7 h 30 min" -> SleepText.SleptFor(Duration.ofMinutes(450)),
    "slept 7h30" -> SleepText.SleptFor(Duration.ofMinutes(450)),
    "nap 20 min" -> SleepText.NapFor(Duration.ofMinutes(20)),
    "nap 1h" -> SleepText.NapFor(Duration.ofHours(1)),
    "nap 13:00 13:40" -> SleepText.NapBetween(time("13:00"), time("13:40")),
    "23:30 7:10" -> SleepText.NotSleep,
    "slept" -> SleepText.NotSleep,
    "slept well" -> SleepText.NotSleep,
    "slept 25:00 7:10" -> SleepText.NotSleep,
    "slept 0h" -> SleepText.NotSleep,
    "I slept 23:30 7:10 I think" -> SleepText.NotSleep,
    "250 ml" -> SleepText.NotSleep
  ).foreach: (text, expected) =>
    test(s"'$text' reads as $expected"):
      assertEquals(SleepTextParser.free(text), expected)

  // ROADMAP G2.22: the Ukrainian words of the same grammar.
  List(
    "сон 23:30 7:10" -> SleepText.Night(time("23:30"), time("07:10")),
    "спав 23:40 до 07:10" -> SleepText.Night(time("23:40"), time("07:10")),
    "спала 0:15-6:45" -> SleepText.Night(time("00:15"), time("06:45")),
    "Спав 7 год" -> SleepText.SleptFor(Duration.ofHours(7)),
    "спала 7 год 30 хв" -> SleepText.SleptFor(Duration.ofMinutes(450)),
    "дрімав 20 хв" -> SleepText.NapFor(Duration.ofMinutes(20)),
    "дрімала 13:00 13:40" -> SleepText.NapBetween(time("13:00"), time("13:40")),
    "денний сон 1 год" -> SleepText.NapFor(Duration.ofHours(1)),
    "спав добре" -> SleepText.NotSleep
  ).foreach: (text, expected) =>
    test(s"'$text' reads as $expected"):
      assertEquals(SleepTextParser.free(text), expected)

  test("the argument of /sleep is two clock times and nothing else"):
    assertEquals(SleepTextParser.times("23:30 7:10"), Some((time("23:30"), time("07:10"))))
    assertEquals(SleepTextParser.times("23:30"), None)
    assertEquals(SleepTextParser.times("goal 8h"), None)
end SleepRulesSuite
