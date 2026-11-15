package dosegram.app

import dosegram.core.domain.insights.DateRange
import dosegram.core.domain.insights.SleepBar
import dosegram.core.domain.insights.SleepRegularityFigures
import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealType
import dosegram.core.ports.CardLook
import dosegram.core.ports.CardPage
import dosegram.core.ports.DayCardView
import dosegram.core.ports.DoseMark
import dosegram.core.ports.FoodCardView
import dosegram.core.ports.FoodLineView
import dosegram.core.ports.ImageTheme
import dosegram.core.ports.MoodCardView
import dosegram.core.ports.SleepCardView
import dosegram.core.ports.WaterCardView
import dosegram.infra.RenderSettings
import dosegram.infra.perf.Timings

import java.time.LocalDate
import java.time.LocalTime

/** docs/PERFORMANCE.md, image render time per card: each status card laid out and drawn by the resvg binary of this
  * machine through the production drawing (`StatusImages.from`), with a fresh store per draw so no shared card is
  * served from the store. Run: `./mill app.test.runMain dosegram.app.renderProbe <resvg> <fonts dir> <samples>
  * [worker]`; with the path of `dosegram-raster` as the fourth argument the long-lived worker draws.
  */
@main def renderProbe(resvg: String, fonts: String, samples: Int, worker: String*): Unit =
  val today = LocalDate.parse("2026-11-13")
  val look = CardLook(ImageTheme.Light, isDiscreet = false)
  val water = WaterCardView(today, totalMl = 1250, goalMl = 2000, entries = 4)
  val mood = MoodCardView(Valence.of(4).get, Energy.of(2))
  val marks = List(DoseMark.TakenOnTime, DoseMark.TakenLate, DoseMark.Skipped, DoseMark.Open)
  val day = DayCardView(today, Some(mood), Some(marks), Some(water), Some(SleepCardView(Some(430), 450)),
    Some(FoodLineView(2, 3)))
  val window = DateRange.endingOn(today, 14)
  val bars = (0 until 14).toList.map(i => SleepBar(today.minusDays(i.toLong), 330 + i, 780 - i, isNap = false))
  val sleep = SleepRegularityFigures(window, bars, 14, None, None, None, None, None, Some(450))
  val food = FoodCardView(today, Map(MealType.Breakfast -> LocalTime.of(8, 10), MealType.Dinner -> LocalTime.of(19, 40)),
    1, FoodLineView(2, 3))
  val settings = Some(RenderSettings(resvg, fonts, worker.headOption))
  def fresh: StatusImages = StatusImages.from(settings, StatusImages.Stores.inMemory, line => println(line))
  // A personal card is keyed by its bytes, so it is drawn every time; the mood card is shared by its state and needs
  // a fresh store per draw to be drawn at all.
  val images = fresh
  def drawn(on: StatusImages, page: StatusImages => Option[CardPage]): Unit =
    on.drawing.draw(page(on).getOrElse(sys.error("no page")), None).fold(e => sys.error(e), _ => ())
  val probes = List(
    Timings.of("card.mood (shared, fresh store)", 3, samples)(drawn(fresh, _.cards.mood(mood, look))),
    Timings.of("card.mood (shared, stored)", 3, samples)(drawn(images, _.cards.mood(mood, look))),
    Timings.of("card.water", 3, samples)(drawn(images, _.cards.water(water, look))),
    Timings.of("card.day", 3, samples)(drawn(images, _.cards.day(day, look))),
    Timings.of("card.sleep (14 nights)", 3, samples)(drawn(images, _.cards.sleep(sleep, look))),
    Timings.of("card.food.day", 3, samples)(drawn(images, _.cards.food(food, look))),
    Timings.of("layout only, card.day", 3, samples)(images.cards.day(day, look))
  )
  println(Timings.Header)
  probes.foreach(probe => println(probe.row))
