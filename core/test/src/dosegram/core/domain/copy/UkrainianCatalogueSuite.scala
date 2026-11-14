package dosegram.core.domain.copy

/** ROADMAP G2.22: the Ukrainian catalogue. Every key has a Ukrainian value, every value keeps the arguments of its
  * English template, the per-locale lints hold, and button labels fit BOT_UX BUX-KB-01.
  */
class UkrainianCatalogueSuite extends munit.FunSuite:

  private def english: List[CopyEntry] = Lang.within(Lang.En)(Copy.all)
  private def ukrainian: List[CopyEntry] = Lang.within(Lang.Uk)(Copy.all)

  /** Operator alerts go to the owner's chat only and stay English (supervisor decision, 2026-11-14, owner asleep). */
  private def operatorOnly(id: String): Boolean = id.startsWith("ops.")

  /** Entries whose Ukrainian text is the same as the English one on purpose. */
  private val SameInBothLanguages =
    Set("language.name.en", "language.name.uk", "language.changed.en", "language.changed.uk")

  /** Entries whose Ukrainian example leaves out the 12-hour form ("9pm"), so their numbers differ on purpose. */
  private val ExamplesDiffer = Set(
    "wizard.times.prompt",
    "wizard.times.other_prompt",
    "input.time.empty",
    "input.time.not_a_time",
    "input.duration.empty",
    "input.duration.not_a_duration",
    "input.duration.out_of_range"
  )

  private val Cyrillic = "\\p{IsCyrillic}".r

  test("the locale follows the account's choice, then the client's language tag, then English (R-LOC-1)"):
    assertEquals(Lang.resolve(Some("uk"), Some("en")), Lang.Uk)
    assertEquals(Lang.resolve(Some("en"), Some("uk")), Lang.En)
    assertEquals(Lang.resolve(None, Some("uk-UA")), Lang.Uk)
    assertEquals(Lang.resolve(None, Some("uk_UA")), Lang.Uk)
    assertEquals(Lang.resolve(None, Some("de")), Lang.En)
    assertEquals(Lang.resolve(Some("fr"), None), Lang.En)
    assertEquals(Lang.resolve(None, None), Lang.En)

  test("copy outside a binding is English, and a binding ends with its body"):
    assertEquals(Lang.current, Lang.En)
    assertEquals(Lang.within(Lang.Uk)(Labels.Back), "Назад")
    assertEquals(Labels.Back, "Back")

  test("Ukrainian plural categories: one, few, many for whole numbers and other for fractions (R-LOC-3)"):
    val cases = List(
      0L -> PluralCategory.Many,
      1L -> PluralCategory.One,
      2L -> PluralCategory.Few,
      4L -> PluralCategory.Few,
      5L -> PluralCategory.Many,
      11L -> PluralCategory.Many,
      12L -> PluralCategory.Many,
      14L -> PluralCategory.Many,
      21L -> PluralCategory.One,
      22L -> PluralCategory.Few,
      25L -> PluralCategory.Many,
      101L -> PluralCategory.One,
      111L -> PluralCategory.Many,
      1001L -> PluralCategory.One
    )
    cases.foreach((n, category) => assertEquals(Plural.category(Lang.Uk, n), category, clue(n)))
    assertEquals(Plural.categoryOf(Lang.Uk, BigDecimal("1.5")), PluralCategory.Other)
    assertEquals(Plural.categoryOf(Lang.Uk, BigDecimal("3.0")), PluralCategory.Few)
    assertEquals(Plural.category(Lang.En, 1), PluralCategory.One)
    assertEquals(Plural.category(Lang.En, 2), PluralCategory.Other)

  test("a Ukrainian count takes the form of its number"):
    val days = UkNouns.Days
    assertEquals(List(1L, 2L, 5L, 21L, 22L, 11L).map(days.count), List("1 день", "2 дні", "5 днів", "21 день", "22 дні", "11 днів"))
    assertEquals(days.of(PluralCategory.Other), "дня")

  test("numbers use the decimal separator of the locale (R-LOC-4)"):
    assertEquals(Lang.within(Lang.En)(WaterCopy.volume(1250)), "1.25 l")
    assertEquals(Lang.within(Lang.Uk)(WaterCopy.volume(1250)), "1,25 л")

  test("every English key has a Ukrainian value (R-LOC-2)"):
    assertEquals(ukrainian.map(_.id), english.map(_.id))
    val en = english.map(e => e.id -> e.text).toMap
    val untranslated = ukrainian.filterNot(e => operatorOnly(e.id) || SameInBothLanguages(e.id)).collect {
      case entry if entry.text == en(entry.id) || Cyrillic.findFirstIn(entry.text).isEmpty => entry.id
    }
    assertEquals(untranslated, Nil)

  test("every Ukrainian value keeps the numbers and the user's words of its English template"):
    val en = english.map(e => e.id -> e.text).toMap
    def numbers(text: String): List[String] = "\\d+".r.findAllIn(text).toList.sorted
    val userWords = List("Vitamin D", "Vitamin C", "1000 IU", "Europe/Kyiv", "Atlantis", "/water", "/mood", "/sleep")
    val mismatched = ukrainian.filterNot(e => ExamplesDiffer(e.id)).flatMap { entry =>
      val english = en(entry.id)
      val numbersDiffer = Option.when(numbers(english) != numbers(entry.text))(s"${entry.id}: numbers")
      val wordsLost = userWords.filter(word => english.contains(word) && !entry.text.contains(word))
      numbersDiffer.toList ++ wordsLost.map(word => s"${entry.id}: '$word'")
    }
    assertEquals(mismatched, Nil)

  test("no Ukrainian entry contains a forbidden phrase, and a seeded one is caught (R-LOC-8)"):
    val violations = ukrainian.flatMap(e => ForbiddenPhrases.violations(e.text).map(p => s"${e.id}: '$p'"))
    assertEquals(violations, Nil)
    assert(ForbiddenPhrases.violations("Прийміть зараз подвійну дозу.").nonEmpty)
    assert(ForbiddenPhrases.violations("Ви знову забули.").nonEmpty)
    assertEquals(ForbiddenPhrases.violations("Записано о 09:03."), Nil)

  test("the Ukrainian statistics, insight and mood entries are free of causal and evaluative words"):
    val linted = ukrainian.filter(e => Set("stats", "insight", "mood")(e.id.takeWhile(_ != '.')))
    assert(linted.nonEmpty)
    val violations = linted.flatMap(e => InsightPhrases.violations(e.text).map(p => s"${e.id}: '$p'"))
    assertEquals(violations, Nil)
    assert(InsightPhrases.violations("Вода покращує ваш настрій.").nonEmpty)
    assert(InsightPhrases.violations("Вам варто пити більше.").nonEmpty)
    assertEquals(InsightPhrases.violations("Це порівняння ваших власних днів. Воно не показує причину."), Nil)

  test("every Ukrainian button label fits 24 characters (BOT_UX BUX-KB-01)"):
    val labels = Lang.within(Lang.Uk):
      Labels.entries.map(e => e.id -> e.text) ++
        List(
          "consent.agree" -> ConsentCopy.Agree,
          "consent.not_now" -> ConsentCopy.NotNow,
          "consent.confirm" -> ConsentCopy.Confirm,
          "consent.go_back" -> ConsentCopy.GoBack,
          "water.goal" -> WaterCopy.Goal,
          "water.no_goal" -> WaterCopy.NoGoal,
          "water.image" -> WaterCopy.ShowAsImage,
          "water.preset" -> WaterCopy.preset(250),
          "checkin.energy" -> CheckInCopy.AddEnergy,
          "sleep.night" -> SleepCopy.GoingToSleep,
          "sleep.up" -> SleepCopy.ImUp,
          "food.edit" -> FoodCopy.EditInApp,
          "prompt.snooze" -> PromptCopy.Snooze1h,
          "prompt.pause" -> PromptCopy.PauseToday,
          "prompt.add" -> PromptCopy.AddFromPreset,
          "prompt.day" -> PromptCopy.DayProfile,
          "report.stop" -> ReportCopy.StopThese,
          "report.previous" -> ReportCopy.PreviousMonth,
          "report.patterns" -> ReportCopy.Patterns,
          "insight.compare" -> InsightCopy.CompareAcrossAreas,
          "export.data" -> ExportCopy.ExportMyData,
          "export.summary" -> ExportCopy.RecordSummary,
          "export.download" -> ExportCopy.DownloadInApp,
          "export.send" -> ExportCopy.SendHere,
          "menu.open_app" -> MenuCopy.openApp,
          "wizard.other_time" -> WizardCopy.otherTime,
          "wizard.change_days" -> WizardCopy.changeDays,
          "wizard.change_timezone" -> WizardCopy.changeTimezoneLabel
        ) ++ MenuCopy.topLevel.map("menu.main" -> _) ++
        dosegram.core.domain.nutrition.MealType.values.toList.map(meal => "food.button" -> FoodCopy.label(meal))
    val tooLong = labels.collect { case (id, label) if label.length > 24 => s"$id: '$label' (${label.length})" }
    assertEquals(tooLong, Nil)
end UkrainianCatalogueSuite
