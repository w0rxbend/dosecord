package dosecord.core.chat

import java.nio.file.Files
import java.nio.file.Path

import scala.io.Source
import scala.util.Using

/** Golden-file helper (DESIGN.md section 2: in-repo golden-file helper for renderer snapshots). Files live at
  * `core/test/resources/goldens/<profile>/<scenario>.golden`; regenerate with
  * `./mill core.test.runMain dosecord.core.chat.rendererGoldenGenerate`.
  */
object GoldenFiles:
  val ResourceDir = "core/test/resources/goldens"

  def name(scenario: String, profile: String): String = s"$profile/$scenario.golden"

  def load(scenario: String, profile: String): String =
    val path = s"/goldens/${name(scenario, profile)}"
    val stream = Option(getClass.getResourceAsStream(path))
      .getOrElse(throw IllegalStateException(s"$path is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

  def expected(scenario: String, profile: String): String =
    val (ops, report) = GoldenScenarios.renderAll((scenario, profile))
    GoldenFormat.render(scenario, profile, ops, report)

  def matrix: List[(String, String)] =
    GoldenScenarios.renderAll.keys.toList.sortBy((s, p) => (p, s))

@main def rendererGoldenGenerate(): Unit =
  val dir = Path.of(GoldenFiles.ResourceDir)
  GoldenFiles.matrix.foreach { (scenario, profile) =>
    val target = dir.resolve(GoldenFiles.name(scenario, profile))
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, GoldenFiles.expected(scenario, profile))
  }
  println(s"wrote ${GoldenFiles.matrix.size} renderer goldens to ${GoldenFiles.ResourceDir}")
