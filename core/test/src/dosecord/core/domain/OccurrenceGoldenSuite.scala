package dosecord.core.domain

import java.time.LocalTime

import scala.io.Source
import scala.util.Using

class OccurrenceGoldenSuite extends munit.FunSuite:

  private val goldenText: String =
    val stream = Option(getClass.getResourceAsStream("/occurrence-golden.txt"))
      .getOrElse(throw IllegalStateException("occurrence-golden.txt is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

  private val goldenRows: List[OccurrenceGolden.Row] =
    goldenText.linesIterator.filterNot(l => l.startsWith("#") || l.isBlank).map(OccurrenceGolden.Row.parse).toList

  private val fixturesByKey: Map[String, OccurrenceGolden.Fixture] =
    OccurrenceGolden.fixtures.map(f => f.key -> f).toMap

  test("golden rows cover all seven zones, a week fixture per zone, and gap and fold candidates"):
    assertEquals(goldenRows.map(_.fixtureKey.split("\\|")(0)).distinct, DstGolden.Zones.map(_.getId))
    assertEquals(
      goldenRows.map(_.fixtureKey).distinct.count(_.endsWith("|week")),
      DstGolden.Zones.size,
      "every zone needs its multi-slot-group week fixture"
    )
    assert(goldenRows.exists(_.dstKind == DstKind.Gap), "no gap rows")
    assert(goldenRows.exists(_.dstKind == DstKind.Fold), "no fold rows")
    assert(goldenRows.exists(_.dstKind == DstKind.None), "no plain rows")

  goldenRows.groupBy(_.fixtureKey).toList.sortBy(_._1).foreach { (key, rows) =>
    test(s"occurrences $key"):
      val fixture = fixturesByKey.getOrElse(
        key,
        throw IllegalArgumentException(s"golden rows reference unknown fixture: $key")
      )
      val actual = Evaluator.occurrences(fixture.revision, fixture.from, fixture.to)
      assertEquals(
        actual.map(c => OccurrenceGolden.Row(key, c.localDate, c.slotKey, c.dstKind, c.scheduledFor).render),
        rows.map(_.render)
      )
      // Re-verify every candidate against the M0.7 primitive directly, so the golden cannot drift
      // together with a resolveLocal regression.
      actual.foreach { c =>
        val (instant, kind) =
          Dst.resolveLocal(c.localDate, LocalTime.of(c.localTime.hour, c.localTime.minute), fixture.revision.zone)
        assertEquals((c.scheduledFor, c.dstKind), (instant, kind))
      }
  }

  test("committed golden file equals a fresh generation from the runtime tzdb"):
    // Same normalization as the DST golden: the `# tzdb=` header records the generating version, and
    // a JDK patch that changes none of the covered transitions must not fail the build.
    def normalized(text: String): String =
      text.linesIterator.map(l => if l.startsWith("# tzdb=") then "# tzdb=<normalized>" else l).mkString("\n")
    assert(
      normalized(goldenText) == normalized(OccurrenceGolden.content),
      s"golden file drifted from the runtime tzdb; regenerate: ./mill core.test.runMain dosecord.core.domain.occurrenceGoldenGenerate ${OccurrenceGolden.ResourcePath}"
    )
