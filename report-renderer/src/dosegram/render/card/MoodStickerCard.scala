package dosegram.render.card

import dosegram.render.blob.BlobFigure
import dosegram.render.blob.BlobFrame
import dosegram.render.blob.MoodState
import dosegram.render.scene.Point
import dosegram.render.scene.Scene
import dosegram.render.scene.Size

/** A sticker of "My mood stickers" (supervisor decision of 2026-11-28): the blob of one mood state alone on a
  * transparent 512 px square, with the doubled outline of a transparent sticker (VISUALS section 3.3) and no ground
  * mark and no words. It is the same picture for everyone in that state, in every theme and language; its text
  * equivalent is the state's level name, which the set's emoji also carries.
  */
object MoodStickerCard:
  val TemplateId = "card.mood.sticker.v1"

  /** The revision of the sticker drawing. It is part of the key of every stored sticker, so a change of it never reuses
    * an old picture (VA-BL-11).
    */
  val Revision: String = "dg-sticker-1"
  val SidePx: Int = 512

  // Centred a little high, so the halo of the widest state stays inside the square with a margin.
  private val BlobAt = BlobFrame(Point(256, 250), BlobFigure.StickerRadius)

  /** The colour the contrast of the outline is judged against: the assumed dark chat background (VISUALS A4). */
  private val ChatBackground = dosegram.render.color.Rgb.hex("#0e1621")

  def render(state: MoodState, context: CardContext): CardOutput =
    val scene =
      Scene(
        TemplateId,
        Size(SidePx, SidePx),
        ChatBackground,
        Vector(BlobFigure.sticker(state, BlobAt)),
        transparent = true
      )
    val name = context.copy.valenceName(state.valence)
    CardOutput(scene, TextEquivalent(name, name), "dosegram-mood-sticker.png", hasSpoiler = false)
