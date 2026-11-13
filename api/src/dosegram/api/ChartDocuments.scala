package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import sttp.tapir.Schema

// A chart is a scene (ARCHITECTURE section 9.5.1; MINI_APP section 5.6): the server positions every primitive and the
// client paints them. Colours are token names of MINI_APP section 5.4, so a scene follows the theme of the client.

enum SceneShape(val wire: String):
  case Rect extends SceneShape("rect")
  case Line extends SceneShape("line")
  case Circle extends SceneShape("circle")
  case Text extends SceneShape("text")

  /** An invisible rectangle over a data mark: what a tap or a key selects. */
  case Hit extends SceneShape("hit")

object SceneShape:
  given ReadWriter[SceneShape] = ApiJson.wireEnum(values, _.wire)
  given Schema[SceneShape] = Schema.derivedEnumeration[SceneShape](encode = Some(_.wire))

enum SceneTone(val wire: String):
  case Medication extends SceneTone("domain-medication")
  case Hydration extends SceneTone("domain-hydration")
  case Mood extends SceneTone("domain-mood")
  case Sleep extends SceneTone("domain-sleep")
  case Recorded extends SceneTone("recorded")
  case Ink extends SceneTone("text")
  case Muted extends SceneTone("text-muted")
  case Separator extends SceneTone("separator")

object SceneTone:
  given ReadWriter[SceneTone] = ApiJson.wireEnum(values, _.wire)
  given Schema[SceneTone] = Schema.derivedEnumeration[SceneTone](encode = Some(_.wire))

/** A second encoding beside the tone, so that shade is never the only cue (MINI_APP MA-A11Y-6): an outline marks a
  * value that is not a recorded one, a dash marks a reference line.
  */
enum SceneStyle(val wire: String):
  case Outlined extends SceneStyle("outlined")
  case Dashed extends SceneStyle("dashed")

object SceneStyle:
  given ReadWriter[SceneStyle] = ApiJson.wireEnum(values, _.wire)
  given Schema[SceneStyle] = Schema.derivedEnumeration[SceneStyle](encode = Some(_.wire))

enum SceneAnchor(val wire: String):
  case Start extends SceneAnchor("start")
  case Middle extends SceneAnchor("middle")
  case End extends SceneAnchor("end")

object SceneAnchor:
  given ReadWriter[SceneAnchor] = ApiJson.wireEnum(values, _.wire)
  given Schema[SceneAnchor] = Schema.derivedEnumeration[SceneAnchor](encode = Some(_.wire))

/** One primitive in the coordinates of its scene. `x`, `y` are the corner of a rectangle, the start of a line, the
  * centre of a circle or the anchor of a text; `x2`, `y2` end a line; `radius` rounds a rectangle or sizes a circle.
  * `mark` is the index of the chart mark the node belongs to.
  */
final case class SceneNode(
    shape: SceneShape,
    x: Double,
    y: Double,
    tone: SceneTone,
    width: Option[Double] = None,
    height: Option[Double] = None,
    x2: Option[Double] = None,
    y2: Option[Double] = None,
    radius: Option[Double] = None,
    text: Option[String] = None,
    anchor: Option[SceneAnchor] = None,
    opacity: Option[Double] = None,
    style: Option[SceneStyle] = None,
    mark: Option[Int] = None
) derives ReadWriter,
      Schema

final case class Scene(width: Int, height: Int, nodes: List[SceneNode]) derives ReadWriter, Schema

/** The data of a chart as rows of text: the first cell of a row names it. */
final case class ChartTable(caption: String, columns: List[String], rows: List[List[String]]) derives ReadWriter, Schema

/** A chart with its text equivalent (MINI_APP MA-INS-5): `summary` is one sentence from the same data and the
  * accessible name of the drawing, `table` holds every value, and `marks` holds the exact value of each selectable mark
  * in reading order (MA-INS-6).
  */
final case class Chart(
    id: String,
    title: String,
    summary: String,
    scene: Scene,
    table: ChartTable,
    marks: List[String]
) derives ReadWriter,
      Schema
