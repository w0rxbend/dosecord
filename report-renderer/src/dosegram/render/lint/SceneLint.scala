package dosegram.render.lint

import dosegram.render.color.Rgb
import dosegram.render.scene.Align
import dosegram.render.scene.Channel
import dosegram.render.scene.Node
import dosegram.render.scene.Paint
import dosegram.render.scene.Placement
import dosegram.render.scene.Scene
import dosegram.render.scene.Stroke
import dosegram.render.text.TextMeasurer
import dosegram.render.tokens.TextStyle

/** What a scene is checked against: the colours it may use, the surfaces its text sits on, and the text measurer. */
final case class LintRules(palette: Set[Rgb], textBackgrounds: Set[Rgb], measurer: TextMeasurer)

/** The scene lints of VISUALS section 11.1: minimum text and stroke sizes (VA-TY-01, VA-LA-01), text contrast
  * (VA-CO-05), the palette whitelist (VA-ID-06, VA-PR-08), gradients only inside the blob (VA-ID-02), a non-colour
  * channel on every status mark (VA-PR-06), and text inside the canvas with the corner safe zone.
  */
object SceneLint:
  val MinimumStrokePx: Double = 4
  val MinimumTextContrast: Double = 4.5
  val SafeMarginPx: Double = 48
  private val BlobRole = "blob"

  def violations(scene: Scene, rules: LintRules): Vector[String] =
    placed(scene.nodes, Placement(0, 0, 1), insideBlob = false).flatMap(check(scene, rules, _)).distinct

  /** A leaf or mark with the transform of its enclosing groups, and whether a blob mark encloses it. */
  final private case class Placed(node: Node, placement: Placement, insideBlob: Boolean)

  private def placed(nodes: Vector[Node], outer: Placement, insideBlob: Boolean): Vector[Placed] =
    nodes.flatMap {
      case Node.Group(inner, children) =>
        val combined =
          Placement(outer.dx + inner.dx * outer.scale, outer.dy + inner.dy * outer.scale, outer.scale * inner.scale)
        placed(children, combined, insideBlob)
      case mark: Node.Mark =>
        Placed(mark, outer, insideBlob) +: placed(mark.children, outer, insideBlob || mark.meta.role == BlobRole)
      case leaf => Vector(Placed(leaf, outer, insideBlob))
    }

  private def check(scene: Scene, rules: LintRules, item: Placed): Vector[String] =
    item.node match
      case Node.Mark(meta, _) if (meta.channels - Channel.Colour).isEmpty =>
        Vector(s"mark '${meta.role}' is carried by colour alone")
      case text: Node.Text              => textViolations(scene, rules, text, item.placement)
      case rect: Node.Rect              => shapeViolations(rules, item, rect.fill, rect.stroke)
      case circle: Node.Circle          => shapeViolations(rules, item, circle.fill, circle.stroke)
      case ellipse: Node.Ellipse        => shapeViolations(rules, item, ellipse.fill, ellipse.stroke)
      case path: Node.Path              => shapeViolations(rules, item, path.fill, path.stroke)
      case line: Node.Line              => shapeViolations(rules, item, None, Some(line.stroke))
      case _: Node.Mark | _: Node.Group => Vector.empty

  private def shapeViolations(
      rules: LintRules,
      item: Placed,
      fill: Option[Paint],
      stroke: Option[Stroke]
  ): Vector[String] =
    val thin = stroke.filter(_.width * item.placement.scale < MinimumStrokePx).map { thinStroke =>
      s"stroke of ${thinStroke.width * item.placement.scale} px is under $MinimumStrokePx px"
    }
    val gradient = fill.collect { case _: Paint.Radial if !item.insideBlob => "gradient outside the blob" }
    val colours = fill.toVector.flatMap {
      case Paint.Solid(color)               => Vector(color)
      case Paint.Radial(_, _, inner, outer) => Vector(inner, outer)
    } ++ stroke.map(_.color)
    thin.toVector ++ gradient ++ colours.filterNot(rules.palette).map(color => s"colour ${color.hex} is not a token")

  private def textViolations(scene: Scene, rules: LintRules, text: Node.Text, placement: Placement): Vector[String] =
    val size = text.style.sizePx * placement.scale
    val width = rules.measurer.width(text.content, text.style) * placement.scale
    val anchorX = placement.dx + text.anchor.x * placement.scale
    val left = text.align match
      case Align.Start  => anchorX
      case Align.Middle => anchorX - width / 2
      case Align.End    => anchorX - width
    val baseline = placement.dy + text.anchor.y * placement.scale
    val isInside = left >= SafeMarginPx && left + width <= scene.size.width - SafeMarginPx &&
      baseline - size * 0.75 >= SafeMarginPx && baseline <= scene.size.height - SafeMarginPx
    val lowContrast = rules.textBackgrounds.filter(text.ink.contrast(_) < MinimumTextContrast)
    Vector(
      Option.when(size < TextStyle.MinimumSizePx)(s"text '${text.content}' is $size px, under the minimum"),
      Option.when(!isInside)(s"text '${text.content}' leaves the canvas safe area"),
      Option.when(!rules.palette(text.ink))(s"text ink ${text.ink.hex} is not a token"),
      Option.when(lowContrast.nonEmpty)(s"text '${text.content}' has contrast under $MinimumTextContrast")
    ).flatten
