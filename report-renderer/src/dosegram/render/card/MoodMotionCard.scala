package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.BlobMotion
import dosegram.render.blob.BlobPalette
import dosegram.render.blob.MoodReading
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Size

/** An animated card before it is drawn: one scene per frame of the loop, the frame rate, the text equivalent from the
  * same view model, a neutral file name and the spoiler flag.
  */
final case class MotionOutput(
    templateId: String,
    frames: Vector[Scene],
    framesPerSecond: Int,
    text: TextEquivalent,
    fileName: String,
    hasSpoiler: Boolean
)

/** The animated mood blob (VISUALS section 4.6, supervisor decision of 2026-11-28): the blob of one mood state on its
  * tinted background, 512 px square, moving by [[BlobMotion]]. It carries no words: the level names are in the caption
  * of the message (VA-BL-04), which is the text equivalent of the still card. Like the still card it is a global asset,
  * the same picture for everyone in that state and theme.
  */
object MoodMotionCard:
  val TemplateId = "card.mood.motion.v1"

  /** The revision of the motion and of its encoding. It is part of the key of every stored or uploaded animation, so a
    * change of either never reuses an old video (VA-BL-11).
    */
  val Revision: String = "dg-motion-1"
  val SidePx: Int = 512
  private val BlobAt = BlobFrame(Point(256, 240), 152)

  def render(reading: MoodReading, context: CardContext): MotionOutput =
    val tokens = context.tokens
    val background = BlobPalette.background(reading.state, context.theme)
    val frames = (0 until BlobMotion.FrameCount).toVector.map: index =>
      val blob = BlobFigure.of(reading.state, BlobAt, tokens, BlobMotion.pose(reading.state, index))
      Scene(TemplateId, Size(SidePx, SidePx), background, Vector(blob))
    val text = TextEquivalent.of(context.copy.moodText(Some(reading)), context.copy.moodLoggedDiscreet, context)
    MotionOutput(TemplateId, frames, BlobMotion.FramesPerSecond, text, "dosegram-mood.mp4", hasSpoiler = false)
