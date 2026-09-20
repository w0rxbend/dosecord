package dosecord.core.domain

import scala.io.Source
import scala.util.Using

class DstGoldenSuite extends munit.FunSuite:

  private val goldenText: String =
    val stream = Option(getClass.getResourceAsStream("/dst-golden.txt"))
      .getOrElse(throw IllegalStateException("dst-golden.txt is not on the test classpath"))
    Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).get

  private val goldenRows: List[GoldenRow] =
    goldenText.linesIterator.filterNot(l => l.startsWith("#") || l.isBlank).map(GoldenRow.parse).toList

  test("golden rows cover all seven zones and all three DstKind values"):
    assertEquals(goldenRows.map(_.zone).distinct, DstGolden.Zones)
    assert(goldenRows.exists(_.kind == DstKind.Gap), "no gap rows")
    assert(goldenRows.exists(_.kind == DstKind.Fold), "no fold rows")
    assert(goldenRows.exists(_.kind == DstKind.None), "no plain rows")

  goldenRows.foreach { row =>
    test(s"golden ${row.zone.getId} ${row.local} -> ${row.kind.dbValue} ${row.instant}"):
      val (instant, kind) = Dst.resolveLocal(row.local, row.zone)
      assertEquals(kind, row.kind)
      assertEquals(instant, row.instant)
  }

  test("committed golden file equals a fresh generation from the runtime tzdb"):
    // The `# tzdb=` header records the version the file was generated with; it is normalized out so
    // a JDK patch upgrade that changes none of the seven zones' 2000-2030 transitions does not fail
    // the build. A tzdb change that alters any transition in the window still fails this test.
    def normalized(text: String): String =
      text.linesIterator.map(l => if l.startsWith("# tzdb=") then "# tzdb=<normalized>" else l).mkString("\n")
    assert(
      normalized(goldenText) == normalized(DstGolden.content),
      s"golden file drifted from the runtime tzdb; regenerate: ./mill core.test.runMain dosecord.core.domain.dstGoldenGenerate ${DstGolden.ResourcePath}"
    )
