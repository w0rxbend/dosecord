package dosegram.infra.crypto

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import scala.util.Try

/** One key-encryption key (ARCHITECTURE SEC-02): 256 bits and the id stored next to every data key it wraps. Its text
  * form shows the id only.
  */
final class Kek private (val id: String, private[crypto] val material: Array[Byte]):
  override def toString: String = s"Kek($id, ***)"

object Kek:
  val Bytes: Int = 32

  private val IdPattern = "[A-Za-z0-9_-]{1,32}"

  def apply(id: String, material: Array[Byte]): Kek =
    require(id.matches(IdPattern), "a key-encryption key id is 1 to 32 of A-Z a-z 0-9 _ -")
    require(material.length == Bytes, s"a key-encryption key is $Bytes bytes")
    new Kek(id, material.clone())

/** The configured key-encryption keys: `active` wraps every new or rewrapped data key; `retired` keys only unwrap, so a
  * rotation (SEC-07) rewraps the data keys at the next start and the old key can then be removed.
  */
final case class KekRing(active: Kek, retired: List[Kek]):
  def find(id: String): Option[Kek] = (active :: retired).find(_.id == id)

object KekRing:

  /** The id of [[development]]; refused in a configured value so that no deployment names it by accident. */
  val DevelopmentId = "development"

  /** The key of development and tests (ARCHITECTURE section 16.1): derived from a fixed label, so a development
    * database stays readable across restarts. It is not a secret, and production refuses to start without its own.
    */
  val development: KekRing =
    val material = MessageDigest
      .getInstance("SHA-256")
      .digest("dosegram development key-encryption key; not a secret".getBytes(UTF_8))
    KekRing(Kek(DevelopmentId, material), Nil)

  /** `KEK` = comma-separated `<id>:<base64>` pairs of 32-byte keys; the first is active, the rest only unwrap. No
    * message quotes key material.
    */
  def parse(raw: String): Either[String, KekRing] =
    val entries = raw.split(",").toList.map(_.trim).filter(_.nonEmpty)
    val parsed = entries.map(parseEntry)
    parsed.collectFirst { case Left(problem) => problem } match
      case Some(problem) => Left(problem)
      case None          =>
        val keys = parsed.collect { case Right(kek) => kek }
        keys match
          case Nil                                            => Left("KEK: at least one key is required")
          case _ if keys.map(_.id).distinct.size != keys.size => Left("KEK: key ids must be distinct")
          case _ if keys.exists(_.id == DevelopmentId)        => Left(s"KEK: the id '$DevelopmentId' is reserved")
          case active :: retired                              => Right(KekRing(active, retired))

  private def parseEntry(entry: String): Either[String, Kek] =
    entry.split(":", 2).toList match
      case List(id, encoded) =>
        for
          _ <- Either.cond(
            id.matches("[A-Za-z0-9_-]{1,32}"),
            (),
            s"KEK: key id '$id' is not 1 to 32 of A-Z a-z 0-9 _ -"
          )
          material <- decode(encoded).toRight(s"KEK: key '$id' is not base64")
          _ <- Either.cond(material.length == Kek.Bytes, (), s"KEK: key '$id' is not ${Kek.Bytes} bytes")
        yield Kek(id, material)
      case _ => Left("KEK: an entry is not '<id>:<base64>'")

  private def decode(encoded: String): Option[Array[Byte]] =
    Try(Base64.getDecoder.decode(encoded)).orElse(Try(Base64.getUrlDecoder.decode(encoded))).toOption
