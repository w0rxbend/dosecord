package dosegram.render

import dosegram.render.card.Privacy
import dosegram.render.tokens.Theme

import java.util.concurrent.atomic.AtomicInteger

class CardRendererSuite extends munit.FunSuite:
  private val card = Samples.waters(1).render(Samples.context(Theme.Dark, Privacy.Discreet)).head

  /** A rasteriser that returns a fixed answer and counts its calls. */
  final private class StubRasteriser(answer: Either[RasterFailure, PngImage]) extends Rasteriser:
    val calls: AtomicInteger = AtomicInteger(0)

    def rasterise(svg: String, zoom: Int): Either[RasterFailure, PngImage] =
      calls.incrementAndGet()
      answer

  test("a rendered image carries the template, the file name, the text equivalent and the spoiler flag of its card"):
    val png = PngImage(IArray[Byte](1, 2, 3))
    val image = CardRenderer(StubRasteriser(Right(png))).render(card).fold(failure => fail(failure.toString), identity)
    assertEquals(image.templateId, "card.water.v1")
    assertEquals(image.fileName, "dosegram-water.png")
    assertEquals(image.text, card.text)
    assertEquals(image.hasSpoiler, true)
    assertEquals(image.png.toArray.toVector, Vector[Byte](1, 2, 3))

  test("a document outside the profile is refused and never reaches the rasteriser"):
    val stub = StubRasteriser(Right(PngImage(IArray.empty[Byte])))
    val outside = """<svg xmlns="http://www.w3.org/2000/svg"><image href="file:///etc/passwd"/></svg>"""
    assertEquals(CardRenderer(stub).document(outside), Left(RasterFailure.OutsideProfile(2)))
    assertEquals(stub.calls.get, 0)

  test("a rasteriser failure reaches the caller as a value, so the text equivalent can be sent instead (VA-FB-01)"):
    assertEquals(CardRenderer(StubRasteriser(Left(RasterFailure.TimedOut))).render(card).left.toOption, Some(RasterFailure.TimedOut))
