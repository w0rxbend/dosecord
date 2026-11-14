package dosegram.infra

import dosegram.core.ports.PurgeList

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.util.UUID

/** The purge list as a file outside the database (ARCHITECTURE SEC-16): one line per deleted account, the instant and
  * the account id, appended and forced to the disk before the deletion goes on. The restore script of ROADMAP G2.19
  * replays it. The file holds internal account ids only, never a name or a vendor id.
  */
final class FilePurgeList(path: Path) extends PurgeList:

  override def append(accountId: UUID, at: Instant): Unit = synchronized:
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val channel = FileChannel.open(path, CREATE, WRITE, APPEND)
    try
      channel.write(ByteBuffer.wrap(s"$at $accountId\n".getBytes(UTF_8)))
      channel.force(true)
    finally channel.close()

object FilePurgeList:

  /** The account ids a purge list holds, in the order they were deleted. */
  def read(path: Path): List[UUID] =
    if !Files.exists(path) then Nil
    else
      Files
        .readAllLines(path, UTF_8)
        .toArray(Array.empty[String])
        .toList
        .filter(_.nonEmpty)
        .map(line => UUID.fromString(line.split(' ')(1)))
