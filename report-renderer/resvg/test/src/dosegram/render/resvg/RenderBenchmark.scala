package dosegram.render.resvg

import dosegram.render.CardRenderer
import dosegram.render.Samples
import dosegram.render.card.CardOutput
import dosegram.render.card.Privacy
import dosegram.render.svg.SvgEmitter
import dosegram.render.tokens.Theme

/** The measurement behind `docs/spikes/render-pipeline.md`: the cold first render of a fresh JVM, then per template
  * the time to build the scene and emit SVG and the time to rasterise, over a number of warm samples. Run it with
  * `./mill report-renderer.resvg.test.runMain dosegram.render.resvg.measureRenders 100`.
  */
object RenderBenchmark:
  val Templates: Vector[String] = Vector("mood/pleasant-high", "day/partial", "calendar/typical", "strip/fortnight", "water/partial", "week/typical", "milestone/50-mood")

  final case class Timing(name: String, sceneMillis: Vector[Double], rasterMillis: Vector[Double], svgBytes: Int, pngBytes: Int)

  def percentile(samples: Vector[Double], share: Double): Double =
    val sorted = samples.sorted
    sorted(StrictMath.min(sorted.size - 1, StrictMath.ceil(share * sorted.size).toInt - 1))

  def millisOf[A](work: => A): (A, Double) =
    val started = System.nanoTime()
    val result = work
    (result, (System.nanoTime() - started) / 1e6)

  def measure(name: String, sampleCount: Int, renderer: CardRenderer): Timing =
    val fixture = Samples.all.find(_.name == name).getOrElse(throw IllegalArgumentException(s"no fixture $name"))
    def build(): CardOutput = fixture.render(Samples.context(Theme.Dark, Privacy.Named)).head
    val samples = Vector.fill(sampleCount) {
      val ((card, svg), sceneMillis) = millisOf { val card = build(); (card, SvgEmitter.emit(card.scene)) }
      val (image, rasterMillis) = millisOf(renderer.render(card))
      val png = image.fold(failure => throw IllegalStateException(s"$name: $failure"), _.png)
      (sceneMillis, rasterMillis, svg.length, png.byteCount)
    }
    Timing(name, samples.map(_._1), samples.map(_._2), samples.last._3, samples.last._4)

@main def measureRenders(sampleCount: Int): Unit =
  val binary = ResvgBinary.locate.getOrElse(throw IllegalStateException("resvg is not on the PATH and DOSEGRAM_RESVG is not set"))
  val renderer = CardRenderer(ResvgRasteriser(ResvgBinary.config(binary)))
  val (_, coldMillis) = RenderBenchmark.millisOf(RenderBenchmark.measure("day/partial", 1, renderer))
  println(f"cold first render (scene + SVG + rasterise, fresh JVM): $coldMillis%.0f ms")
  println("template | scene+svg p50 | scene+svg p95 | raster p50 | raster p95 | raster max | svg bytes | png bytes")
  val timings = RenderBenchmark.Templates.map(RenderBenchmark.measure(_, sampleCount, renderer))
  timings.foreach { timing =>
    println(
      f"${timing.name} | ${RenderBenchmark.percentile(timing.sceneMillis, 0.5)}%.2f ms | ${RenderBenchmark.percentile(timing.sceneMillis, 0.95)}%.2f ms | " +
        f"${RenderBenchmark.percentile(timing.rasterMillis, 0.5)}%.1f ms | ${RenderBenchmark.percentile(timing.rasterMillis, 0.95)}%.1f ms | " +
        f"${timing.rasterMillis.max}%.1f ms | ${timing.svgBytes} | ${timing.pngBytes}"
    )
  }
  val all = timings.flatMap(_.rasterMillis)
  println(f"all templates, ${all.size} samples: raster p50 ${RenderBenchmark.percentile(all, 0.5)}%.1f ms, p95 ${RenderBenchmark.percentile(all, 0.95)}%.1f ms")
  val runtime = Runtime.getRuntime
  System.gc()
  println(f"JVM heap in use after the run: ${(runtime.totalMemory - runtime.freeMemory) / 1e6}%.1f MB (max ${runtime.maxMemory / 1e6}%.0f MB)")
