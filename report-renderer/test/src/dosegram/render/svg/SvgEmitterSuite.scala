package dosegram.render.svg

import dosegram.render.Samples
import dosegram.render.card.MedicationRow
import dosegram.render.card.MedicationStripCard
import dosegram.render.card.Privacy
import dosegram.render.card.StripData
import dosegram.render.chart.DoseStatus
import dosegram.render.color.Rgb
import dosegram.render.scene.Box
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.Placement
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Size
import dosegram.render.tokens.TextStyle
import dosegram.render.tokens.Theme
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.io.StringReader
import java.time.LocalDate
import javax.xml.parsers.DocumentBuilderFactory

import org.xml.sax.InputSource

class SvgEmitterSuite extends munit.ScalaCheckSuite:
  private val White = Rgb.hex("#ffffff")
  private val Ink = Rgb.hex("#13242e")

  private def scene(nodes: Node*): Scene = Scene("test.scene", Size(200, 100), White, nodes.toVector)

  private def parses(svg: String): Boolean =
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.newDocumentBuilder().parse(InputSource(StringReader(svg))).getDocumentElement.getTagName == "svg"

  test("numbers are written with at most two decimals and no exponent"):
    assertEquals(SvgEmitter.number(12), "12")
    assertEquals(SvgEmitter.number(12.5), "12.5")
    assertEquals(SvgEmitter.number(12.345), "12.35")
    assertEquals(SvgEmitter.number(-0.004), "0")
    assertEquals(SvgEmitter.number(-3.1), "-3.1")
    assertEquals(SvgEmitter.number(1e7), "10000000")

  test("a coordinate that is not finite is rejected, never written"):
    intercept[IllegalArgumentException](SvgEmitter.number(Double.NaN))
    intercept[IllegalArgumentException](SvgEmitter.number(Double.PositiveInfinity))

  test("a scene is emitted as an opaque full-bleed background followed by its nodes"):
    val svg = SvgEmitter.emit(scene(Node.filledRect(Box(10, 20, 30, 40), 4, Ink)))
    assertEquals(
      svg,
      """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100" viewBox="0 0 200 100">
        |<rect width="200" height="100" fill="#ffffff"/>
        |<rect x="10" y="20" width="30" height="40" rx="4" fill="#13242e"/>
        |</svg>
        |""".stripMargin
    )

  test("markup in text is escaped and characters XML cannot carry are dropped"):
    val svg = SvgEmitter.emit(scene(Node.label("<b>&\"'\u0007￾</b>", Point(10, 50), TextStyle.Label, Ink)))
    assert(svg.contains(">&lt;b&gt;&amp;&quot;&apos;&lt;/b&gt;</text>"), svg)
    assert(parses(svg))

  test("equal gradients share one definition and groups carry their transform"):
    val gradient = Paint.Radial(Point(5, 5), 10, White, Ink)
    val dot = Node.Circle(Point(5, 5), 5, Some(gradient), None)
    val svg = SvgEmitter.emit(scene(dot, Node.Group(Placement(20, 0, 0.5), Vector(dot))))
    assertEquals("<radialGradient".r.findAllIn(svg).size, 1)
    assertEquals("""url\(#g1\)""".r.findAllIn(svg).size, 2)
    assert(svg.contains("""<g transform="translate(20 0) scale(0.5)">"""), svg)

  private val hostileText: Gen[String] =
    Gen.listOf(
      Gen.oneOf(
        Gen.oneOf("<script>", "</svg>", "&amp;", "\"", "'", "<![CDATA[", "]]>", "‮", "⁦", "\u0000", "\ud83d", "🚀", "שלום", "مرحبا", " "),
        Gen.alphaNumStr,
        Gen.asciiPrintableStr
      )
    ).map(_.mkString)

  property("a hostile medication name stays inside the profile and the document still parses (DEP-03)"):
    forAll(hostileText) { name =>
      val strip = StripData(LocalDate.of(2026, 11, 2), 7, Vector(MedicationRow(name, isAsNeeded = false, Vector.fill(7)(Vector(DoseStatus.TakenOnTime)))))
      val svgs = MedicationStripCard.render(strip, Samples.context(Theme.Dark, Privacy.Named)).map(card => SvgEmitter.emit(card.scene))
      svgs.forall(svg => SvgProfile.violations(svg).isEmpty && parses(svg))
    }
