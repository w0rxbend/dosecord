package dosegram.infra

import dosegram.core.ports.PurgeList

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

/** The purge list as a file outside the database (ARCHITECTURE SEC-16): one line per deleted account, the instant and
  * the account id, appended and forced to the disk before the deletion goes on. The restore script of ROADMAP G2.19
  * replays it. The file holds internal account ids only, never a name or a vendor id. It is created readable and
  * writable by its owner only (0600), and a start refuses a list that its group or other users may read or write.
  */
final class FilePurgeList(path: Path) extends PurgeList:

  override def append(accountId: UUID, at: Instant): Unit = synchronized:
    Option(path.toAbsolutePath.getParent).foreach(Files.createDirectories(_))
    val options = Set(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).asJava
    val channel =
      if FilePurgeList.posix(path) then FileChannel.open(path, options, FilePurgeList.OwnerOnly)
      else FileChannel.open(path, options)
    try
      channel.write(ByteBuffer.wrap(s"$at $accountId\n".getBytes(UTF_8)))
      channel.force(true)
    finally channel.close()

object FilePurgeList:

  private val OwnerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))

  private val Shared = Set(
    PosixFilePermission.GROUP_READ,
    PosixFilePermission.GROUP_WRITE,
    PosixFilePermission.OTHERS_READ,
    PosixFilePermission.OTHERS_WRITE
  )

  private def posix(path: Path): Boolean =
    val existing = Option(path.toAbsolutePath.getParent).getOrElse(path.toAbsolutePath)
    Files.getFileStore(existing).supportsFileAttributeView("posix")

  /** The start-up check: a list that its group or other users may read or write is refused, with the command that fixes
    * it. A missing list is fine; the first deletion creates it with mode 0600.
    */
  def requirePrivate(path: Path): Unit =
    if Files.exists(path) && posix(path) then
      val shared = Files.getPosixFilePermissions(path).asScala.toSet.intersect(Shared)
      if shared.nonEmpty then
        throw IllegalStateException(
          s"dosegram: the purge list $path may be read or written by its group or other users; run chmod 600 $path"
        )

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
