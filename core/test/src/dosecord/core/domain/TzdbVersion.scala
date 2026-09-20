package dosecord.core.domain

import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Reads and compares the runtime JDK's tzdb version from `lib/tzdb.dat` (JDK 9+ header: a file-version byte, a UTF
  * "TZDB" magic, a short version count, then the version ids as UTF strings).
  */
object TzdbVersion:

  def runtimeVersion(): String =
    val file = Path.of(System.getProperty("java.home"), "lib", "tzdb.dat")
    val in = DataInputStream(Files.newInputStream(file))
    try
      in.readByte()
      require(in.readUTF() == "TZDB", s"$file is not a JDK tzdb.dat")
      require(in.readShort() >= 1, s"$file carries no tzdb version")
      in.readUTF()
    finally in.close()

  def atLeast(actual: String, required: String): Boolean =
    (parse(actual), parse(required)) match
      case (Some((ay, al)), Some((ry, rl))) => ay > ry || (ay == ry && al >= rl)
      case _                                => false

  private def parse(version: String): Option[(Int, Char)] =
    if version.length == 5 && version.take(4).forall(_.isDigit) && version(4).isLetter then
      Some((version.take(4).toInt, version(4)))
    else scala.None
