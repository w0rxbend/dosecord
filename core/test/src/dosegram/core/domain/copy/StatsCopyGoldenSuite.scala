package dosegram.core.domain.copy

import java.nio.file.Files
import java.nio.file.Path

import scala.io.Source
import scala.util.Using

/** The reviewed wording of every statistics and insight template (ROADMAP G2.20, G6.8). A change of a template fails
  * here until the golden is regenerated and read:
  * `./mill core.test.runMain dosegram.core.domain.copy.statsCopyGoldenGenerate`.
  */
object StatsCopyGolden:
  val File = "core/test/resources/goldens/stats/copy.golden"

  def rendered: String =
    StatsCopy.entries.map(entry => s"# ${entry.id}\n${entry.text}\n").mkString("\n")

  def stored: String =
    val stream = Option(getClass.getResourceAsStream("/goldens/stats/copy.golden"))
      .getOrElse(throw IllegalStateException("goldens/stats/copy.golden is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

@main def statsCopyGoldenGenerate(): Unit =
  val target = Path.of(StatsCopyGolden.File)
  Option(target.getParent).foreach(Files.createDirectories(_))
  Files.writeString(target, StatsCopyGolden.rendered)
  println(s"wrote ${StatsCopyGolden.File}")

class StatsCopyGoldenSuite extends munit.FunSuite:

  test("the statistics and insight templates match the reviewed golden"):
    assertEquals(
      StatsCopyGolden.rendered,
      StatsCopyGolden.stored,
      "golden drifted; regenerate: ./mill core.test.runMain dosegram.core.domain.copy.statsCopyGoldenGenerate"
    )

  test("the golden contains no forbidden phrase"):
    assertEquals(InsightPhrases.violations(StatsCopyGolden.stored), Nil)

  test("a zero denominator is printed as No data and never as a percentage"):
    val noData = StatsCopy.entries.find(_.id == "stats.doses.no_data").get.text
    assert(noData.startsWith(s"Doses: ${StatsCopy.NoData}"), noData)
    assert(!noData.contains("%"), noData)

  test("a denominator below five is printed as counts without a percentage"):
    val counts = StatsCopy.entries.find(_.id == "stats.doses.counts").get.text
    assert(counts.startsWith("Doses: 3 of 4 taken"), counts)
    assert(!counts.contains("%"), counts)

  test("a period below its minimum prints what is still needed and no average"):
    List("stats.water.gate", "stats.mood.gate").foreach { id =>
      val gate = StatsCopy.entries.find(_.id == id).get.text
      assert(gate.contains("more"), gate)
      assert(!gate.contains("average 1") && !gate.contains("average 2") && !gate.contains("average 3"), gate)
    }

  test("every period figure of water and mood is printed with its coverage (AC-INS-8)"):
    List("stats.water", "stats.water.no_goal", "stats.water.gate", "stats.mood", "stats.mood.gate").foreach { id =>
      val line = StatsCopy.entries.find(_.id == id).get.text
      assert(line.endsWith("of 7 days have data"), line)
    }
end StatsCopyGoldenSuite
