package dosecord.core.domain

import java.nio.file.Files
import java.nio.file.Path

@main def occurrenceGoldenGenerate(path: String): Unit =
  val target = Path.of(path)
  Option(target.getParent).foreach(Files.createDirectories(_))
  Files.writeString(target, OccurrenceGolden.content)
  println(s"wrote ${OccurrenceGolden.rows.size} golden rows to $path (tzdb ${TzdbVersion.runtimeVersion()})")
