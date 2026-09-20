package dosecord.contracts

import java.time.Instant
import java.time.LocalDate

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import upickle.default.*

import Gens.given

/** Acceptance 3 and 4: envelope source scheme and smart-constructor validation.
  */
class ValidationSuite extends munit.ScalaCheckSuite:

  property("every Envelope.source matches ^dosecord\\.[a-z]+$"):
    forAll { (envelope: Envelope[Command]) =>
      Source.Pattern.matches(envelope.source.value) &&
      read[Envelope[Command]](write(envelope)).source.value.matches("^dosecord\\.[a-z]+$")
    }

  property("source scheme rejects anything outside dosecord.<module>"):
    forAll(Gen.asciiStr) { (s: String) =>
      Source.parse(s).isRight == Source.Pattern.matches(s)
    }

  test("every command and event type carries the .v1 suffix (the single version source)"):
    assert((Command.allTypes ++ Event.allTypes).forall(_.endsWith(".v1")))
    assert((Command.allTypes ++ Event.allTypes).forall(_.matches("^dosecord\\.[a-z]+\\.[a-z_]+\\.v1$")))

  test("HH:MM rejects \"noon\" and other non-24-hour strings"):
    assert(HhMm.parse("noon").isLeft)
    assert(HhMm.parse("9:00").isLeft)
    assert(HhMm.parse("24:00").isLeft)
    assert(HhMm.parse("12:60").isLeft)
    assert(HhMm.parse("").isLeft)
    assertEquals(HhMm.parse("09:00").map(_.hour), Right(9))
    assertEquals(HhMm.parse("23:59").map(_.minute), Right(59))
    intercept[IllegalArgumentException](HhMm.unsafe("noon"))

  test("ReminderPolicyData rejects negative missAfterMinutes"):
    intercept[IllegalArgumentException](ReminderPolicyData(missAfterMinutes = -1))
    intercept[IllegalArgumentException](ReminderPolicyData(missAfterMinutes = 0))
    intercept[IllegalArgumentException](ReminderPolicyData(maxReminders = 0))
    intercept[IllegalArgumentException](ReminderPolicyData(snoozeOptionsMinutes = List(10, -5)))
    intercept[IllegalArgumentException](ReminderPolicyData(snoozeOptionsMinutes = List(10, 10)))

  test("FixedTimeScheduleData rejects duplicate days"):
    intercept[IllegalArgumentException](
      FixedTimeScheduleData(
        "UTC",
        List(Weekday.Mon, Weekday.Mon),
        List(HhMm.unsafe("09:00")),
        LocalDate.of(2026, 9, 20)
      )
    )

  test("Handle rejects @handle and out-of-length values"):
    assert(Handle.parse("@worxbend").isLeft)
    assert(Handle.parse("ab").isLeft)
    assert(Handle.parse("a" * 81).isLeft)
    assert(Handle.parse("has space").isLeft)
    assert(Handle.parse("w0rx_bend-1.ok").isRight)
    intercept[IllegalArgumentException](Handle.unsafe("@worxbend"))

  test("MoodLevel is bounded to 1-10"):
    assert(MoodLevel.parse(0).isLeft)
    assert(MoodLevel.parse(11).isLeft)
    assertEquals(MoodLevel.parse(7).map(_.value), Right(7))
    intercept[IllegalArgumentException](MoodLevel.unsafe(11))

  test("validation also fires when reading JSON"):
    def rejected[T: ReadWriter](json: String): Boolean =
      val ex = intercept[upickle.core.TraceVisitor.TraceException](read[T](json))
      ex.getCause.isInstanceOf[IllegalArgumentException]
    assert(rejected[HhMm](write("noon")))
    assert(rejected[Handle](write("@worxbend")))
    assert(rejected[Source](write("not-a-source")))

  test("envelope fixes specversion, datacontenttype and the .v1 suffix"):
    val envelope = Envelope(
      id = EventId(java.util.UUID.randomUUID()),
      messageType = Command.MoodCheckinRecordRequestedType,
      source = Source.unsafe("dosecord.core"),
      time = Instant.parse("2026-09-20T09:00:00Z"),
      actor = Actor("console", "u1"),
      data = Command.MoodCheckinRecordRequested(MoodLevel.unsafe(8))
    )
    intercept[IllegalArgumentException](envelope.copy(specversion = "0.3"))
    intercept[IllegalArgumentException](envelope.copy(datacontenttype = "text/plain"))
    intercept[IllegalArgumentException](envelope.copy(messageType = "dosecord.mood.checkin_recorded"))
    assertEquals(envelope.actor.subject, "platform:console:u1")
end ValidationSuite
