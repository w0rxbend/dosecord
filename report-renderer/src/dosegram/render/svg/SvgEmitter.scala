package dosegram.render.svg

import dosegram.render.scene.Align
import dosegram.render.scene.LineCap
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.PathCommand
import dosegram.render.scene.PathData
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Stroke
import dosegram.render.text.FontPack

/** Stage 4 of the pipeline (research brief 04 section 7.1): a pure function from a scene to SVG text in "Dosegram SVG
  * profile 1". The same scene always yields the same bytes (VA-PR-11). User text is XML-escaped here and nowhere else.
  */
object SvgEmitter:

  def emit(scene: Scene): String =
    val gradientIds = Node
      .flatten(scene.nodes)
      .flatMap(fillOf)
      .collect { case gradient: Paint.Radial => gradient }
      .distinct
      .zipWithIndex
      .map((gradient, index) => gradient -> s"g${index + 1}")
    val writer = NodeWriter(gradientIds.toMap)
    val width = number(scene.size.width)
    val height = number(scene.size.height)
    val lines =
      Vector(
        s"""<svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $width $height">"""
      )
        ++ definitions(gradientIds)
        ++ Vector(s"""<rect width="$width" height="$height" fill="${scene.background.hex}"/>""")
        ++ scene.nodes.flatMap(writer.lines(_, 0))
        ++ Vector("</svg>")
    lines.mkString("", "\n", "\n")

  /** Fixed-point formatting with at most two decimals, so no platform's `Double.toString` reaches the output. */
  def number(value: Double): String =
    require(!value.isNaN && !value.isInfinite, "a scene coordinate must be finite")
    val hundredths = StrictMath.round(value * 100)
    val sign = if hundredths < 0 then "-" else ""
    val whole = StrictMath.abs(hundredths) / 100
    val fraction = StrictMath.abs(hundredths) % 100
    if fraction == 0 then s"$sign$whole"
    else if fraction % 10 == 0 then s"$sign$whole.${fraction / 10}"
    else f"$sign$whole.$fraction%02d"

  /** Escapes the five XML specials and drops characters XML 1.0 cannot carry. */
  def escape(text: String): String =
    text.codePoints.toArray.iterator
      .filter(isXmlCharacter)
      .map(codePoint => XmlEntities.getOrElse(codePoint, Character.toString(codePoint)))
      .mkString

  private val XmlEntities: Map[Int, String] = Map(
    '&'.toInt -> "&amp;",
    '<'.toInt -> "&lt;",
    '>'.toInt -> "&gt;",
    '"'.toInt -> "&quot;",
    '\''.toInt -> "&apos;"
  )

  private def isXmlCharacter(codePoint: Int): Boolean =
    (codePoint >= 0x20 && codePoint <= 0xd7ff) || (codePoint >= 0xe000 && codePoint <= 0xfffd) || codePoint >= 0x10000

  private def fillOf(node: Node): Option[Paint] =
    node match
      case rect: Node.Rect                                            => rect.fill
      case circle: Node.Circle                                        => circle.fill
      case ellipse: Node.Ellipse                                      => ellipse.fill
      case path: Node.Path                                            => path.fill
      case _: Node.Group | _: Node.Mark | _: Node.Line | _: Node.Text => None

  private def definitions(gradientIds: Vector[(Paint.Radial, String)]): Vector[String] =
    if gradientIds.isEmpty then Vector.empty
    else
      val gradients = gradientIds.map { (gradient, id) =>
        s"""  <radialGradient id="$id" gradientUnits="userSpaceOnUse" cx="${number(gradient.center.x)}" """ +
          s"""cy="${number(gradient.center.y)}" r="${number(gradient.radius)}">""" +
          s"""<stop offset="0" stop-color="${gradient.inner.hex}"/>""" +
          s"""<stop offset="1" stop-color="${gradient.outer.hex}"/></radialGradient>"""
      }
      ("<defs>" +: gradients) :+ "</defs>"

  final private class NodeWriter(gradientIds: Map[Paint.Radial, String]):

    def lines(node: Node, depth: Int): Vector[String] =
      val indent = "  " * depth
      node match
        case Node.Group(placement, children) =>
          val scale = if placement.scale == 1 then "" else s" scale(${number(placement.scale)})"
          val open = s"""$indent<g transform="translate(${number(placement.dx)} ${number(placement.dy)})$scale">"""
          (open +: children.flatMap(lines(_, depth + 1))) :+ s"$indent</g>"
        case Node.Mark(_, children) =>
          (s"$indent<g>" +: children.flatMap(lines(_, depth + 1))) :+ s"$indent</g>"
        case leaf => Vector(indent + element(leaf))

    private def element(leaf: Node): String =
      leaf match
        case Node.Rect(box, radius, fill, stroke) =>
          val corner = if radius == 0 then "" else s""" rx="${number(radius)}""""
          s"""<rect x="${number(box.x)}" y="${number(box.y)}" width="${number(box.width)}" """ +
            s"""height="${number(box.height)}"$corner${paint(fill, stroke)}/>"""
        case Node.Circle(center, radius, fill, stroke) =>
          s"""<circle cx="${number(center.x)}" cy="${number(center.y)}" r="${number(radius)}"${paint(fill, stroke)}/>"""
        case Node.Ellipse(center, radiusX, radiusY, fill, stroke) =>
          s"""<ellipse cx="${number(center.x)}" cy="${number(center.y)}" rx="${number(radiusX)}" """ +
            s"""ry="${number(radiusY)}"${paint(fill, stroke)}/>"""
        case Node.Line(from, to, stroke) =>
          s"""<line x1="${number(from.x)}" y1="${number(from.y)}" x2="${number(to.x)}" y2="${number(to.y)}"""" +
            s"""${strokeAttributes(stroke)}/>"""
        case Node.Path(data, fill, stroke) => s"""<path d="${pathText(data)}"${paint(fill, stroke)}/>"""
        case text: Node.Text               => textElement(text)
        case _: Node.Group | _: Node.Mark  => throw IllegalStateException("containers are written by lines")

    private def textElement(text: Node.Text): String =
      val anchor = text.align match
        case Align.Start  => ""
        case Align.Middle => """ text-anchor="middle""""
        case Align.End    => """ text-anchor="end""""
      s"""<text x="${number(text.anchor.x)}" y="${number(text.anchor.y)}" """ +
        s"""font-family="${FontPack.family(text.style.role)}" font-weight="${text.style.weight}" """ +
        s"""font-size="${number(text.style.sizePx)}" fill="${text.ink.hex}"$anchor>${escape(text.content)}</text>"""

    private def paint(fill: Option[Paint], stroke: Option[Stroke]): String =
      val fillValue = fill match
        case None                         => "none"
        case Some(Paint.Solid(color))     => color.hex
        case Some(gradient: Paint.Radial) => s"url(#${gradientIds(gradient)})"
      s""" fill="$fillValue"${stroke.map(strokeAttributes).getOrElse("")}"""

    private def strokeAttributes(stroke: Stroke): String =
      val cap = stroke.cap match
        case LineCap.Butt  => ""
        case LineCap.Round => """ stroke-linecap="round" stroke-linejoin="round""""
      val dash = stroke.dash.map(d => s""" stroke-dasharray="${number(d.on)} ${number(d.off)}"""").getOrElse("")
      s""" stroke="${stroke.color.hex}" stroke-width="${number(stroke.width)}"$cap$dash"""

  private def pathText(data: PathData): String =
    data.commands
      .map {
        case PathCommand.MoveTo(to)                             => s"M${pair(to)}"
        case PathCommand.LineTo(to)                             => s"L${pair(to)}"
        case PathCommand.QuadTo(control, to)                    => s"Q${pair(control)} ${pair(to)}"
        case PathCommand.CubicTo(c1, c2, to)                    => s"C${pair(c1)} ${pair(c2)} ${pair(to)}"
        case PathCommand.ArcTo(radius, largeArc, clockwise, to) =>
          s"A${number(radius)} ${number(radius)} 0 ${flag(largeArc)} ${flag(clockwise)} ${pair(to)}"
        case PathCommand.Close => "Z"
      }
      .mkString(" ")

  private def pair(point: Point): String = s"${number(point.x)} ${number(point.y)}"

  private def flag(isSet: Boolean): String = if isSet then "1" else "0"
