package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.BlobPalette
import dosegram.render.blob.MoodReading
import dosegram.render.scene.Align
import dosegram.render.scene.Node
import dosegram.render.scene.Point
import dosegram.render.scene.TextKind
import dosegram.render.tokens.TextStyle

/** IMG-02, `card.mood.v1`: the blob of one mood state on its tinted background, with the level name drawn under it
  * (VA-BL-04). It is a global L0 asset: the picture carries nothing personal, and discreet mode changes the caption
  * only.
  */
object MoodCard:
  val TemplateId = "card.mood.v1"
  private val BlobAt = BlobFrame(Point(540, 480), 300)
  private val NameBaseline = 936.0
  private val EnergyBaseline = 1000.0

  def render(mood: Option[MoodReading], context: CardContext): CardOutput =
    val background = mood.map(reading => BlobPalette.background(reading.state, context.theme))
    val frame = CardFrame(context, CardFrame.Square, background.getOrElse(context.tokens.surface))
    val blob = mood
      .map(reading => BlobFigure.of(reading.state, BlobAt, frame.tokens))
      .getOrElse(BlobFigure.noEntry(BlobAt, frame.tokens))
    val text = TextEquivalent.of(context.copy.moodText(mood), context.copy.moodLoggedDiscreet, context)
    CardOutput(
      frame.scene(TemplateId, blob +: labels(mood, frame, context)),
      text,
      "dosegram-mood.png",
      hasSpoiler = false
    )

  private def labels(mood: Option[MoodReading], frame: CardFrame, context: CardContext): Vector[Node] =
    val nameLook = TextLook(TextStyle.Title, frame.tokens.ink, Align.Middle, TextKind.Label)
    val energyLook = TextLook(TextStyle.Body, frame.tokens.ink2, Align.Middle, TextKind.Label)
    val name = mood.map(reading => context.copy.valenceName(reading.state.valence)).getOrElse(context.copy.noMoodEntry)
    val energy = mood.filter(_.isEnergyGiven).map(reading => context.copy.energyLine(reading.state.energy))
    frame.text(name, Point(BlobAt.center.x, NameBaseline), nameLook, frame.width)
      +: energy.map(line => frame.text(line, Point(BlobAt.center.x, EnergyBaseline), energyLook, frame.width)).toVector
