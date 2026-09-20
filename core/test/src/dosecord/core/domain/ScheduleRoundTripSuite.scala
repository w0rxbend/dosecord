package dosecord.core.domain

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll

import upickle.default.ReadWriter
import upickle.default.read
import upickle.default.write

import ScheduleGens.given

/** ROADMAP M1.1 acceptance: any valid policy (and every other M1.1 domain type) round-trips through upickle —
  * `rule`, `reminder_policy` and `quiet_hours` are persisted as jsonb (DESIGN.md sections 7.1 and 8).
  */
class ScheduleRoundTripSuite extends munit.ScalaCheckSuite:

  private def roundTrip[T: Arbitrary: ReadWriter](name: String): Unit =
    property(s"$name round-trips through upickle"):
      forAll { (value: T) =>
        read[T](write(value)) == value
      }

  roundTrip[SlotGroup]("SlotGroup")
  roundTrip[ChainAnchor]("ChainAnchor")
  roundTrip[TaperPhase]("TaperPhase")
  roundTrip[Rule]("Rule")
  roundTrip[QuietHoursMode]("QuietHoursMode")
  roundTrip[ReminderPolicy]("ReminderPolicy")
  roundTrip[QuietHours]("QuietHours")
end ScheduleRoundTripSuite
