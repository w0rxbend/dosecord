package dosecord.core.domain

import java.time.LocalTime

import org.scalacheck.Prop.forAll

import dosecord.contracts.HhMm

import ScheduleGens.genHhMm
import ScheduleGens.genLocalTime

/** ROADMAP M1.1: `QuietHours` is a half-open `[start, end)` interval that may cross midnight; equal bounds mean "no
  * quiet hours".
  */
class QuietHoursSuite extends munit.ScalaCheckSuite:

  private def at(hour: Int, minute: Int): LocalTime = LocalTime.of(hour, minute)
  private def hhmm(value: String): HhMm = HhMm.unsafe(value)

  test("same-day interval contains its bounds half-openly"):
    val quiet = QuietHours(hhmm("09:00"), hhmm("17:00"))
    assert(quiet.isActive)
    assert(quiet.contains(at(9, 0)))
    assert(quiet.contains(at(12, 30)))
    assert(quiet.contains(at(16, 59)))
    assert(!quiet.contains(at(17, 0)))
    assert(!quiet.contains(at(8, 59)))

  test("interval crossing midnight wraps past 24:00"):
    val quiet = QuietHours(hhmm("22:00"), hhmm("06:00"))
    assert(quiet.isActive)
    assert(quiet.contains(at(22, 0)))
    assert(quiet.contains(at(23, 59)))
    assert(quiet.contains(at(0, 0)))
    assert(quiet.contains(at(3, 30)))
    assert(quiet.contains(at(5, 59)))
    assert(!quiet.contains(at(6, 0)))
    assert(!quiet.contains(at(21, 59)))
    assert(!quiet.contains(at(12, 0)))

  test("equal bounds mean no quiet hours"):
    val quiet = QuietHours(hhmm("22:00"), hhmm("22:00"))
    assert(!quiet.isActive)
    assert(!quiet.contains(at(22, 0)))
    assert(!quiet.contains(at(0, 0)))
    assert(!quiet.contains(at(12, 0)))

  property("containment is the complement of the reversed interval"):
    forAll(genHhMm, genHhMm, genLocalTime) { (start, end, t) =>
      val quiet = QuietHours(start, end)
      if quiet.isActive then quiet.contains(t) == !QuietHours(end, start).contains(t)
      else !quiet.contains(t)
    }
end QuietHoursSuite
