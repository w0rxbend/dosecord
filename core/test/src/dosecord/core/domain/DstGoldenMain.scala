package dosecord.core.domain

import java.nio.file.Files
import java.nio.file.Path

@main def dstGoldenGenerate(path: String): Unit =
  val target = Path.of(path)
  Option(target.getParent).foreach(Files.createDirectories(_))
  Files.writeString(target, DstGolden.content)
  println(s"wrote ${DstGolden.rows.size} golden rows to $path (tzdb ${TzdbVersion.runtimeVersion()})")
