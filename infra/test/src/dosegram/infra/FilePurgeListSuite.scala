package dosegram.infra

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID

/** Verification of cycle 9: the purge list names deleted accounts, so it is the owner's file only. */
class FilePurgeListSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-11-15T08:00:00Z")

  private def withDirectory[A](test: Path => A): A =
    val directory = Files.createTempDirectory("purge-list")
    try test(directory)
    finally
      Files.list(directory).forEach(Files.delete(_))
      Files.delete(directory)

  private def mode(path: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(path))

  test("the first deletion creates the list with mode 0600, and a later one keeps it"):
    withDirectory: directory =>
      val path = directory.resolve("purge-list")
      val list = FilePurgeList(path)
      val (first, second) = (UUID.randomUUID(), UUID.randomUUID())
      list.append(first, at)
      assertEquals(mode(path), "rw-------")
      list.append(second, at)
      assertEquals(mode(path), "rw-------")
      assertEquals(FilePurgeList.read(path), List(first, second))
      FilePurgeList.requirePrivate(path)

  test("a start refuses a list its group or other users may read or write, and names the fix"):
    withDirectory: directory =>
      val path = directory.resolve("purge-list")
      FilePurgeList(path).append(UUID.randomUUID(), at)
      List("rw-r-----", "rw----r--", "rw--w----", "rw-r--r--").foreach: shared =>
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(shared))
        val refused = intercept[IllegalStateException](FilePurgeList.requirePrivate(path))
        assert(refused.getMessage.contains(s"chmod 600 $path"), refused.getMessage)
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("r--------"))
      FilePurgeList.requirePrivate(path)

  test("a missing list is no reason to refuse a start"):
    withDirectory(directory => FilePurgeList.requirePrivate(directory.resolve("not-yet")))
