package dosegram.render.text

import dosegram.render.tokens.FontRole
import dosegram.render.tokens.TextStyle

/** One static font file of the pinned pack: the family and weight it answers to in SVG, and its checksum. */
final case class FontFace(file: String, family: String, weight: Int, role: FontRole, sha256: String)

/** The pinned font pack (VISUALS section 3.4, ROADMAP conflict X-11): IBM Plex Sans for text, its condensed width for
  * long names, Geologica for hero numerals and titles. All are SIL OFL 1.1. The files live in `report-renderer/fonts`.
  */
object FontPack:
  val Faces: Vector[FontFace] = Vector(
    FontFace(
      "IBMPlexSans-Regular.ttf",
      "IBM Plex Sans",
      400,
      FontRole.Text,
      "b47ebd72b5ce53b60f909033ff84d6fc12358cc147e2981f8c321ad3f36246b6"
    ),
    FontFace(
      "IBMPlexSans-Medium.ttf",
      "IBM Plex Sans",
      500,
      FontRole.Text,
      "a494d5a69fa094f7071a9ffbc98e86c85337d1a29f3fc1ff7c2e1c46161ddf27"
    ),
    FontFace(
      "IBMPlexSans-SemiBold.ttf",
      "IBM Plex Sans",
      600,
      FontRole.Text,
      "c51119b25f4a296da2b66edf3201b9aa1735091ed7efc1fca0313346f3851a4d"
    ),
    FontFace(
      "IBMPlexSansCondensed-Medium.ttf",
      "IBM Plex Sans Condensed",
      500,
      FontRole.TextCondensed,
      "afbf818e40448e28a979a58aa01c600e066a91661521a69589293f284068722c"
    ),
    FontFace(
      "Geologica-SemiBold.ttf",
      "Geologica",
      600,
      FontRole.Display,
      "b91a06c78559e76112c88a3131de912a253ea11d58e2120f55a61c479f604fe5"
    )
  )

  def family(role: FontRole): String =
    Faces.find(_.role == role).map(_.family).getOrElse(throw IllegalStateException(s"no face for role $role"))

  def families: Set[String] = Faces.map(_.family).toSet

  /** The face a style resolves to. A style outside the pack is a template defect, not a runtime condition. */
  def face(style: TextStyle): FontFace =
    Faces
      .find(face => face.role == style.role && face.weight == style.weight)
      .getOrElse(throw IllegalArgumentException(s"no font face for $style"))
