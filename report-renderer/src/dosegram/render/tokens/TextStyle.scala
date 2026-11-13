package dosegram.render.tokens

/** A typeface role (VISUALS section 3.4). Templates name roles; only the font pack maps a role to a family. */
enum FontRole:
  case Display, Text, TextCondensed

/** One step of the type scale: role, weight and size in pixels on the 1080 px canvas. */
final case class TextStyle(role: FontRole, weight: Int, sizePx: Double):
  def condensed: TextStyle = TextStyle(FontRole.TextCondensed, 500, sizePx)

/** The type scale of VISUALS section 3.4. `Label` is the smallest size any image may use (VA-TY-01). */
object TextStyle:
  val MinimumSizePx: Double = 40

  val Hero: TextStyle = TextStyle(FontRole.Display, 600, 160)
  val Title: TextStyle = TextStyle(FontRole.Display, 600, 64)
  val Value: TextStyle = TextStyle(FontRole.Text, 600, 56)
  val Body: TextStyle = TextStyle(FontRole.Text, 400, 44)
  val BodyMedium: TextStyle = TextStyle(FontRole.Text, 500, 44)
  val BodyStrong: TextStyle = TextStyle(FontRole.Text, 600, 44)
  val Label: TextStyle = TextStyle(FontRole.Text, 500, 40)
