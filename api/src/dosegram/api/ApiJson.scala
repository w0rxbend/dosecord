package dosegram.api

import sttp.tapir.Codec
import sttp.tapir.CodecFormat
import sttp.tapir.DecodeResult
import sttp.tapir.EndpointIO
import sttp.tapir.Schema
import sttp.tapir.stringBodyUtf8AnyFormat

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import scala.util.control.NonFatal

/** The JSON conventions of ARCHITECTURE section 9.4 and MINI_APP section 15.1: camelCase members, an absent member for
  * an absent value, and a request with a member the server does not know is refused (research brief 06, threat API-4).
  */
object ApiJson extends upickle.AttributeTagged:
  override def allowUnknownKeys: Boolean = false
  override def optionsAsNulls: Boolean = true

  given ReadWriter[UUID] = readwriter[String].bimap(_.toString, UUID.fromString)
  given ReadWriter[Instant] = readwriter[String].bimap(_.toString, Instant.parse)
  given ReadWriter[LocalDate] = readwriter[String].bimap(_.toString, LocalDate.parse)

  /** A closed set of wire names; a name outside it fails the read, which the route answers as a validation error. */
  def wireEnum[E](values: Array[E], wire: E => String): ReadWriter[E] =
    readwriter[String].bimap(
      wire,
      name => values.find(wire(_) == name).getOrElse(throw new IllegalArgumentException("unknown enumeration value"))
    )

  private def codec[T: ReadWriter: Schema, F <: CodecFormat](format: F): Codec[String, T, F] =
    Codec
      .json[T]: raw =>
        try DecodeResult.Value(read[T](raw))
        catch case NonFatal(cause) => DecodeResult.Error(raw, cause)
      .apply(value => write(value))
      .format(format)

  def jsonBody[T: ReadWriter: Schema]: EndpointIO.Body[String, T] =
    stringBodyUtf8AnyFormat(codec[T, CodecFormat.Json](CodecFormat.Json()))

  /** RFC 9457 media type for error bodies (API-14). */
  final case class ProblemJson() extends CodecFormat:
    override val mediaType: sttp.model.MediaType = sttp.model.MediaType("application", "problem+json")

  def problemBody[T: ReadWriter: Schema]: EndpointIO.Body[String, T] =
    stringBodyUtf8AnyFormat(codec[T, ProblemJson](ProblemJson()))
