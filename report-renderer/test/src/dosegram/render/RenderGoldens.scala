package dosegram.render

import dosegram.render.blob.MoodReading
import dosegram.render.blob.MoodState
import dosegram.render.card.CardOutput
import dosegram.render.card.MoodCard
import dosegram.render.card.Privacy
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.Theme
import dosegram.render.tokens.TokensCss

import java.nio.file.Files
import java.nio.file.Path

import scala.io.Source
import scala.util.Using

/** The golden files of the render module (research brief 04 section 12, layers 1 and 5): the exact SVG of every
  * fixture in its light, dark and discreet variant, the text equivalent of every fixture at the named and the discreet
  * level, all 36 blob states in both themes (VA-BL-08), and the generated stylesheet. Files live at
  * `report-renderer/test/resources/goldens`; regenerate with
  * `./mill report-renderer.test.runMain dosegram.render.renderGoldenGenerate` and review the diff.
  */
object RenderGoldens:
  val ResourceDir = "report-renderer/test/resources/goldens"

  val Variants: Vector[(String, Theme, Privacy)] =
    Vector(("light", Theme.Light, Privacy.Named), ("dark", Theme.Dark, Privacy.Named), ("discreet", Theme.Dark, Privacy.Discreet))

  /** Every golden as its path under the golden directory and its expected content. */
  def expected: Vector[(String, String)] = cardScenes ++ cardTexts ++ blobScenes :+ ("tokens.css" -> TokensCss.render)

  def load(path: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/goldens/$path"))
      .getOrElse(throw IllegalStateException(s"/goldens/$path is not on the test classpath"))
    Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)

  private def cardScenes: Vector[(String, String)] =
    for
      fixture <- Samples.all
      (variant, theme, privacy) <- Variants
      (card, page) <- fixture.render(Samples.context(theme, privacy)).zipWithIndex
    yield s"cards/${fixture.name}.$variant${pageSuffix(page)}.svg" -> SvgEmitter.emit(card.scene)

  // The text equivalent does not depend on the theme, so it is pinned once per privacy level.
  private def cardTexts: Vector[(String, String)] =
    for
      fixture <- Samples.all
      privacy <- Vector(Privacy.Named, Privacy.Discreet)
    yield
      val pages = fixture.render(Samples.context(Theme.Dark, privacy)).map(textGolden)
      s"cards/${fixture.name}.${privacy.toString.toLowerCase}.txt" -> pages.mkString("\n")

  private def blobScenes: Vector[(String, String)] =
    for
      theme <- Theme.values.toVector
      (id, mood) <- MoodState.all.map(state => state.id -> Some(MoodReading(state, isEnergyGiven = true))) :+ (MoodState.NoEntryId -> None)
    yield
      val card = MoodCard.render(mood, Samples.context(theme, Privacy.Named))
      s"blob/${theme.toString.toLowerCase}/$id.svg" -> SvgEmitter.emit(card.scene)

  private def textGolden(card: CardOutput): String =
    s"""file: ${card.fileName}
       |spoiler: ${card.hasSpoiler}
       |caption: ${card.text.caption}
       |alt: ${card.text.altText}
       |full: ${card.text.full}
       |""".stripMargin

  private def pageSuffix(page: Int): String = if page == 0 then "" else s".p${page + 1}"

@main def renderGoldenGenerate(): Unit =
  val directory = Path.of(RenderGoldens.ResourceDir)
  RenderGoldens.expected.foreach { (path, content) =>
    val target = directory.resolve(path)
    Option(target.getParent).foreach(Files.createDirectories(_))
    Files.writeString(target, content)
  }
  println(s"wrote ${RenderGoldens.expected.size} render goldens to ${RenderGoldens.ResourceDir}")
