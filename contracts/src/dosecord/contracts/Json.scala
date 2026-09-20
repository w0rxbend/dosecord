package dosecord.contracts

import upickle.default.ReadWriter
import upickle.default.readwriter

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** upickle codecs for the JDK value types used across the contracts. String-based so the JSON is self-describing and
  * language-neutral.
  */
object Json:
  given ReadWriter[UUID] = readwriter[String].bimap(_.toString, UUID.fromString)
  given ReadWriter[Instant] = readwriter[String].bimap(_.toString, Instant.parse)
  given ReadWriter[Duration] = readwriter[String].bimap(_.toString, Duration.parse)
  given ReadWriter[ZoneId] = readwriter[String].bimap(_.getId, ZoneId.of)
  given ReadWriter[LocalDate] = readwriter[String].bimap(_.toString, LocalDate.parse)
