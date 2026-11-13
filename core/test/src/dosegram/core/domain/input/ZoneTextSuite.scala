package dosegram.core.domain.input

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.time.Instant
import java.time.ZoneId

/** The typed time-zone grammar of BOT_UX section 15.6: a city, a zone id in any case, or a UTC offset; the result is
  * always an IANA zone id, never a bare offset (BUX-TZ-01).
  */
class ZoneTextSuite extends munit.ScalaCheckSuite:

  private val now = Instant.parse("2026-11-13T15:42:00Z")
  private val preferred = List("Europe/London", "Europe/Kyiv", "Asia/Kolkata", "America/New_York").map(ZoneId.of)

  private def zone(text: String): String =
    ZoneText.parse(text, now, preferred).fold(fail(s"'$text' not found"))(_.getId)

  test("a zone id in any case and with spaces"):
    assertEquals(zone("Europe/Kyiv"), "Europe/Kyiv")
    assertEquals(zone("europe/kyiv"), "Europe/Kyiv")
    assertEquals(zone("America/New York"), "America/New_York")

  test("a city name, without diacritics or case"):
    assertEquals(zone("Kyiv"), "Europe/Kyiv")
    assertEquals(zone("kyiv."), "Europe/Kyiv")
    assertEquals(zone("new york"), "America/New_York")
    assertEquals(zone("São Paulo"), "America/Sao_Paulo")
    assertEquals(zone("sao paulo"), "America/Sao_Paulo")

  test("a UTC offset becomes a zone that has it now, or the Etc zone of a whole hour"):
    assertEquals(zone("UTC+2"), "Europe/Kyiv")
    assertEquals(zone("GMT+02:00"), "Europe/Kyiv")
    assertEquals(zone("+5:30"), "Asia/Kolkata")
    assertEquals(zone("utc-5"), "America/New_York")
    assertEquals(zone("UTC+9"), "Etc/GMT-9")
    assertEquals(zone("UTC"), "UTC")
    assertEquals(zone("gmt"), "UTC")

  test("not a place"):
    assertEquals(ZoneText.parse("Mars/Olympus_Mons", now, preferred), None)
    assertEquals(ZoneText.parse("UTC+15", now, preferred), None)
    assertEquals(ZoneText.parse("UTC+5:45", now, Nil), None)
    assertEquals(ZoneText.parse("", now, preferred), None)

  property("no input throws and every result is a region id"):
    forAll(Gen.oneOf(Gen.asciiStr, Gen.listOf(Gen.choose(Char.MinValue, Char.MaxValue)).map(_.mkString))) { text =>
      ZoneText.parse(text, now, preferred).forall(z => !z.getId.startsWith("+") && !z.getId.startsWith("-"))
    }
