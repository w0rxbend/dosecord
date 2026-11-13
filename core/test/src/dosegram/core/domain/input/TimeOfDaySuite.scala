package dosegram.core.domain.input

import dosegram.contracts.HhMm
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

/** The shared time-of-day grammar (BOT_UX section 15.6): every input the owner's report names, the deliberate
  * readings of the ambiguous ones, and the two properties (every HH:mm round-trips, nothing throws).
  */
class TimeOfDaySuite extends munit.ScalaCheckSuite:

  private def times(text: String): List[String] =
    TimeOfDay.parseList(text).fold(r => fail(s"'$text' rejected: $r"), _.map(_.value))

  private def one(text: String): String =
    times(text) match
      case List(single) => single
      case other        => fail(s"'$text' read as $other, expected one time")

  test("the owner's case: 9:00 is 09:00"):
    assertEquals(one("9:00"), "09:00")

  test("every spelling of nine o'clock in the morning"):
    List("9", "9:00", "09:00", "9.00", "9 00", "900", "0900", "9am", "9 am", "9 a.m.", "9AM", "9h", "9h00", "09h00")
      .foreach(text => assertEquals(one(text), "09:00", text))

  test("afternoon and evening spellings"):
    assertEquals(one("9:30pm"), "21:30")
    assertEquals(one("9:30 PM"), "21:30")
    assertEquals(one("21"), "21:00")
    assertEquals(one("21h"), "21:00")
    assertEquals(one("21h30"), "21:30")
    assertEquals(one("2130"), "21:30")
    assertEquals(one("930"), "09:30")
    assertEquals(one("9 30"), "09:30")

  test("words for noon and midnight"):
    assertEquals(one("noon"), "12:00")
    assertEquals(one("midday"), "12:00")
    assertEquals(one("midnight"), "00:00")
    assertEquals(one("12am"), "00:00")
    assertEquals(one("12pm"), "12:00")
    assertEquals(one("24:00"), "00:00")

  test("several times in one message, sorted and without repeats"):
    assertEquals(times("8, 14:30 and 22"), List("08:00", "14:30", "22:00"))
    assertEquals(times("22; 8"), List("08:00", "22:00"))
    assertEquals(times("8:00 20:00"), List("08:00", "20:00"))
    assertEquals(times("9, 9:00, 09:00"), List("09:00"))
    assertEquals(times("8am & 8pm"), List("08:00", "20:00"))
    assertEquals(times("8\n20"), List("08:00", "20:00"))

  test("two numbers separated by a space: minutes only when they cannot be an hour or look like minutes"):
    assertEquals(times("9 00"), List("09:00"))
    assertEquals(times("9 30"), List("09:30"))
    assertEquals(times("7 05"), List("07:05"))
    assertEquals(times("9 50"), List("09:50"))
    assertEquals(times("8 20"), List("08:00", "20:00"))
    assertEquals(times("8 22"), List("08:00", "22:00"))

  test("surrounding words and trailing punctuation"):
    assertEquals(one("at 9"), "09:00")
    assertEquals(one("@ 9:15"), "09:15")
    assertEquals(one("around 7pm"), "19:00")
    assertEquals(one("9 o'clock"), "09:00")
    assertEquals(one("9:00."), "09:00")
    assertEquals(one("9!"), "09:00")
    assertEquals(one("  9:00  "), "09:00")
    assertEquals(times("at 8 and at 20."), List("08:00", "20:00"))

  test("Unicode digits and separators"):
    assertEquals(one("９：００"), "09:00")       // full-width
    assertEquals(one("٩:٠٠"), "09:00")          // Arabic-Indic
    assertEquals(one("९:३०"), "09:30")          // Devanagari
    assertEquals(one("9 am"), "09:00")     // no-break space
    assertEquals(one("9​:00"), "09:00")    // zero-width space

  test("a number without am or pm is read on the 24-hour clock"):
    assertEquals(one("1"), "01:00")
    assertEquals(one("7"), "07:00")

  test("rejects name what was not understood"):
    def rejected(text: String): String =
      TimeOfDay.parseList(text) match
        case Left(TimeOfDay.Rejection.NotATime(part)) => part
        case other                                    => fail(s"'$text' gave $other")
    assertEquals(rejected("25:00"), "25:00")
    assertEquals(rejected("9:60"), "9:60")
    assertEquals(rejected("13pm"), "13pm")
    assertEquals(rejected("8, 9:75"), "9:75")
    assertEquals(rejected("breakfast"), "breakfast")
    assertEquals(rejected("9,30"), "30")

  test("empty input and too many times are their own rejections"):
    assertEquals(TimeOfDay.parseList("   "), Left(TimeOfDay.Rejection.Empty))
    assertEquals(TimeOfDay.parseList("at"), Left(TimeOfDay.Rejection.Empty))
    assertEquals(TimeOfDay.parseList("1, 2, 3, 4, 5, 6, 7"), Left(TimeOfDay.Rejection.TooMany(TimeOfDay.MaxTimes)))

  private val genHhMm: Gen[(Int, Int)] = for h <- Gen.choose(0, 23); m <- Gen.choose(0, 59) yield (h, m)

  property("every HH:mm round-trips"):
    forAll(genHhMm) { (h, m) =>
      val text = f"$h%02d:$m%02d"
      TimeOfDay.parseList(text) == Right(List(HhMm.unsafe(text)))
    }

  property("every H:mm and H.mm reads as the same time"):
    forAll(genHhMm) { (h, m) =>
      val expected = Right(List(HhMm.unsafe(f"$h%02d:$m%02d")))
      TimeOfDay.parseList(f"$h:$m%02d") == expected && TimeOfDay.parseList(f"$h.$m%02d") == expected
    }

  private val genNoise: Gen[String] =
    Gen.oneOf(
      Gen.asciiStr,
      Gen.listOf(Gen.choose(Char.MinValue, Char.MaxValue)).map(_.mkString),
      Gen.listOf(Gen.oneOf("9", ":", " ", ",", "am", "pm", "h", ".", "٩", "：", "​", "noon")).map(_.mkString)
    )

  property("no input throws"):
    forAll(genNoise) { text =>
      TimeOfDay.parseList(text)
      true
    }
