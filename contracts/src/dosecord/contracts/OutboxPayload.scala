package dosecord.contracts

import upickle.default.ReadWriter

/** The payload of a standalone `react` outbox row (DESIGN.md section 7.6): which annotation to toggle on the target
  * message. Serialisation lives in contracts so the core stays free of a JSON library (R56).
  */
final case class ReactPayload(emoji: String, on: Boolean) derives ReadWriter:
  require(emoji.nonEmpty, "emoji must not be empty")

object ReactPayload:
  def toJson(payload: ReactPayload): String = upickle.default.write(payload)
  def fromJson(json: String): ReactPayload = upickle.default.read[ReactPayload](json)
