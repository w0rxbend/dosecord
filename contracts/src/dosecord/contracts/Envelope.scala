package dosecord.contracts

import upickle.default.ReadWriter
import upickle.default.macroRW
import upickle.default.readwriter
import upickle.implicits.key

import java.time.Instant
import scala.util.matching.Regex

import Json.given

/** The single source scheme: every envelope is sourced from `dosecord.<module>` (C6).
  */
opaque type Source = String
object Source:
  val Pattern: Regex = "^dosecord\\.[a-z]+$".r

  def parse(value: String): Either[String, Source] =
    if Pattern.matches(value) then Right(value)
    else Left(s"source must match ${Pattern.regex}, got: $value")

  def unsafe(value: String): Source =
    parse(value).fold(msg => throw IllegalArgumentException(msg), identity)

  given ReadWriter[Source] =
    readwriter[String].bimap(identity, value => parse(value).fold(msg => throw IllegalArgumentException(msg), identity))

  extension (source: Source) def value: String = source

/** CloudEvents 1.0 envelope transcribed from the retired prototype's `shared/contracts/envelope.py` (DESIGN.md section
  * 8: `domain_events.data` keeps this shape). Two deliberate changes: there is one `source` scheme
  * (`dosecord.<module>`, see [[Source]]) and the version lives only in the `.v1` suffix of the `type` field — the old
  * `schema_version` field is gone so there is a single version source (R61).
  */
final case class Envelope[A](
    id: EventId,
    @key("type") messageType: String,
    source: Source,
    time: Instant,
    actor: Actor,
    data: A,
    subject: Option[String] = None,
    specversion: String = Envelope.SpecVersion,
    datacontenttype: String = Envelope.DataContentType,
    dataschema: Option[String] = None,
    correlationId: Option[String] = None,
    causationId: Option[String] = None,
    traceparent: Option[String] = None
):
  require(specversion == Envelope.SpecVersion, s"specversion must be ${Envelope.SpecVersion}")
  require(datacontenttype == Envelope.DataContentType, "datacontenttype must be application/json")
  require(messageType.endsWith(".v1"), "message type must carry the .v1 version suffix")

object Envelope:
  val SpecVersion = "1.0"
  val DataContentType = "application/json"

  given [A: ReadWriter]: ReadWriter[Envelope[A]] = macroRW
