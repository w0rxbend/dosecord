package dosegram.app

import dosegram.api.ApiJson
import dosegram.render.blob.BlobGeometry
import dosegram.render.blob.MoodState

import java.nio.charset.StandardCharsets.UTF_8

/** The blob asset of `GET /mood/blob-states` is the renderer's geometry, read off its scene nodes (MINI_APP section
  * 7.9): every drawn state is present, the outline is the one the chat image draws, the states that the valence slider
  * blends share their number of segments (MA-MOOD-5), and the asset stays inside its budget (MINI_APP section 10.2).
  */
class MoodBlobAssetSuite extends munit.FunSuite:

  private val asset = MoodBlobAsset.states
  private def segments(outline: List[Double]): Int = (outline.size - 2) / 6

  test("every drawn state and the no-entry state are present, each once"):
    val ids = asset.states.map(_.id)
    assertEquals(ids.distinct.size, ids.size)
    assertEquals(ids.toSet, MoodState.all.map(_.id).toSet + MoodState.NoEntryId)

  test("an outline is the renderer's outline: one cubic segment per sample, starting at the first sample"):
    MoodState.all.foreach: state =>
      val drawn = asset.states.find(_.id == state.id).get
      val samples = BlobGeometry.samples(state)
      assertEquals(segments(drawn.outline), samples.size, state.id)
      assertEqualsDouble(drawn.outline.head, samples.head.x, 1e-4)
      assertEqualsDouble(drawn.outline(1), samples.head.y, 1e-4)
      assertEquals((drawn.outline.size - 2) % 6, 0, state.id)

  test("the states of one energy level share their segment count, so the slider blends them number by number"):
    MoodState.all.groupBy(_.energy).foreach: (energy, states) =>
      val counts = states.map(state => segments(asset.states.find(_.id == state.id).get.outline)).distinct
      assertEquals(counts.size, 1, s"energy $energy")

  test("a drawn state has a face with two eyes and a mouth; the no-entry state has none and a dotted outline"):
    val none = asset.states.find(_.id == MoodState.NoEntryId).get
    assert(none.isDotted && none.face.isEmpty)
    asset.states.filterNot(_.id == MoodState.NoEntryId).foreach: state =>
      val face = state.face.get
      assertEquals(face.eyes.size, 2, state.id)
      assertEquals(face.mouth.size, 6, state.id)
      assert(state.light.fillOuter.matches("#[0-9a-f]{6}"), state.id)
      assertEquals(state.light.fillOuter, state.dark.fillOuter, "the fill follows the state, not the theme")

  test("the asset fits the budget of 40 kB before compression"):
    val bytes = ApiJson.write(asset).getBytes(UTF_8).length
    assert(bytes < 40 * 1024, s"$bytes bytes")
end MoodBlobAssetSuite

/** Writes the asset that the Mini App's mock backend serves, so development and the component tests draw the real
  * geometry: `./mill app.test.runMain dosegram.app.moodBlobFixture miniapp/src/mock/blob-states.json`.
  */
@main def moodBlobFixture(path: String): Unit =
  java.nio.file.Files.writeString(java.nio.file.Path.of(path), ApiJson.write(MoodBlobAsset.states) + "\n")
  println(s"wrote the mood blob asset to $path")
