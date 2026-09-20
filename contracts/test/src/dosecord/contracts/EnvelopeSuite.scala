package dosecord.contracts

import java.time.Instant
import java.util.UUID

import upickle.default.*

/** The CloudEvents field names survive the transcription from the retired prototype's envelope.
  */
class EnvelopeSuite extends munit.FunSuite:

  private val envelope = Envelope(
    id = EventId(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff")),
    messageType = Command.MoodCheckinRecordRequestedType,
    source = Source.unsafe("dosecord.core"),
    time = Instant.parse("2026-09-20T09:00:00Z"),
    actor = Actor("console", "u1"),
    data = Command.MoodCheckinRecordRequested(MoodLevel.unsafe(8), Some("slept well"), List("sleep")),
    subject = Some("platform:console:u1"),
    correlationId = Some("corr-1")
  )

  test("JSON keeps the CloudEvents 1.0 field names"):
    val json = ujson.read(write(envelope))
    // upickle omits fields equal to their defaults; specversion/datacontenttype are pinned by the
    // constructor's requires whether or not they appear on the wire.
    assert(!json.obj.get("specversion").exists(_.str != "1.0"))
    assert(!json.obj.get("datacontenttype").exists(_.str != "application/json"))
    assertEquals(json("type").str, "dosecord.mood.checkin_record_requested.v1")
    assertEquals(json("source").str, "dosecord.core")
    assert(!json.obj.contains("schema_version"), "the .v1 suffix of `type` is the single version source")

  test("messageType is derived from the payload"):
    assertEquals(Command.messageType(envelope.data), Command.MoodCheckinRecordRequestedType)
