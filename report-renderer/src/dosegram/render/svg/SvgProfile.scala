package dosegram.render.svg

import dosegram.render.text.FontPack

/** "Dosegram SVG profile 1" (research brief 04 section 7.3): the element and attribute whitelist that keeps a document
  * renderable by the rasteriser, free of any file or network reference, and inside the inline-SVG subset of message
  * widgets. A document outside the profile is a defect and is not rasterised.
  */
object SvgProfile:
  private val Elements =
    Set("svg", "g", "defs", "rect", "circle", "ellipse", "line", "path", "text", "radialGradient", "stop")

  private val Attributes = Set(
    "xmlns",
    "width",
    "height",
    "viewBox",
    "transform",
    "x",
    "y",
    "rx",
    "ry",
    "cx",
    "cy",
    "r",
    "x1",
    "y1",
    "x2",
    "y2",
    "d",
    "fill",
    "stroke",
    "stroke-width",
    "stroke-linecap",
    "stroke-linejoin",
    "stroke-dasharray",
    "font-family",
    "font-weight",
    "font-size",
    "text-anchor",
    "id",
    "gradientUnits",
    "offset",
    "stop-color"
  )

  // The emitter escapes `<` and `"` in text content, so these patterns only ever match real markup.
  private val ElementPattern = """<([A-Za-z][A-Za-z0-9:_-]*)""".r
  private val AttributePattern = """\s([A-Za-z][A-Za-z0-9:_-]*)="([^"]*)"""".r
  private val LocalReference = """url\(#g[0-9]+\)""".r

  /** Every way the document leaves the profile; empty when it conforms. */
  def violations(svg: String): Vector[String] =
    val elements = ElementPattern.findAllMatchIn(svg).map(_.group(1)).toVector
    val attributes = AttributePattern.findAllMatchIn(svg).map(found => found.group(1) -> found.group(2)).toVector
    elements.distinct.filterNot(Elements).map(name => s"element <$name> is outside the profile")
      ++ attributes.map(_._1).distinct.filterNot(Attributes).map(name => s"attribute $name is outside the profile")
      ++ attributes.flatMap((name, value) => valueViolation(name, value)).distinct

  private def valueViolation(name: String, value: String): Option[String] =
    name match
      case "font-family" if !FontPack.families(value) => Some(s"font family '$value' is not in the font pack")
      case "fill" | "stroke" if value.contains("url(") && !LocalReference.matches(value) =>
        Some(s"$name refers outside the document")
      case _ => None
