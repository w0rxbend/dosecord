package dosegram.render.motion

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** A reader of the ISO base media boxes of an MP4 file, enough to check what Telegram's `sendAnimation` and a player
  * look at: the brand, the movie duration, the sample description and the sample count.
  */
final case class Mp4Box(kind: String, payload: ByteBuffer, children: Vector[Mp4Box]):
  def child(path: String*): Option[Mp4Box] =
    path.toList match
      case Nil          => Some(this)
      case head :: rest => children.find(_.kind == head).flatMap(_.child(rest*))

object Mp4Boxes:
  private val Containers = Set("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf")

  def parse(bytes: Array[Byte]): Vector[Mp4Box] = boxes(ByteBuffer.wrap(bytes))

  private def boxes(buffer: ByteBuffer): Vector[Mp4Box] =
    Iterator
      .unfold(buffer.duplicate()): rest =>
        Option.when(rest.remaining >= 8):
          val shortSize = rest.getInt
          val kindBytes = Array.ofDim[Byte](4)
          rest.get(kindBytes)
          val kind = String(kindBytes, StandardCharsets.US_ASCII)
          // Size 1: a 64-bit size follows the type.
          val (size, header) = if shortSize == 1 then (rest.getLong.toInt, 16) else (shortSize, 8)
          val payload = rest.slice(rest.position(), size - header)
          rest.position(rest.position() + size - header)
          val children = if Containers(kind) then boxes(payload) else Vector.empty
          (Mp4Box(kind, payload, children), rest)
      .toVector

  /** The movie duration in seconds from `mvhd` (version 0). */
  def seconds(top: Vector[Mp4Box]): Double =
    val header = top.find(_.kind == "moov").flatMap(_.child("mvhd")).get.payload
    header.getInt(16).toDouble / header.getInt(12)

  /** The sample count from `stsz`. */
  def samples(top: Vector[Mp4Box]): Int =
    top.find(_.kind == "moov").flatMap(_.child("trak", "mdia", "minf", "stbl", "stsz")).get.payload.getInt(8)

  /** The four-character code of the first sample entry in `stsd`. */
  def codec(top: Vector[Mp4Box]): String =
    val stsd = top.find(_.kind == "moov").flatMap(_.child("trak", "mdia", "minf", "stbl", "stsd")).get.payload
    val code = Array.ofDim[Byte](4)
    stsd.duplicate().position(12).get(code)
    String(code, StandardCharsets.US_ASCII)
