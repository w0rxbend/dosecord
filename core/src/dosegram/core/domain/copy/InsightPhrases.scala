package dosegram.core.domain.copy

import java.util.Locale
import java.util.regex.Pattern

/** The wording rules of VISUALS_AND_ANALYTICS section 8.6 as a lint (VA-IN-10, ARCHITECTURE INS-11): a statistic or an
  * insight states counts over the user's own records. It never states a cause, never advises, never judges a value and
  * never uses a clinical word. The lists extend [[ForbiddenPhrases]] and apply to the statistics, insight and mood
  * namespaces of the catalogue, where even everyday words such as "so" or "try" would turn a count into a claim.
  *
  * Matching is case-insensitive on whole words: "so" does not match inside "also".
  */
object InsightPhrases:

  /** Verbs and connectives that state a cause. */
  val causal: List[String] = List(
    "causes",
    "cause of",
    "caused",
    "leads to",
    "led to",
    "makes",
    "helps",
    "harms",
    "improves",
    "improved",
    "worsens",
    "boosts",
    "reduces",
    "affects",
    "triggers",
    "results in",
    "because",
    "due to",
    "thanks to",
    "so",
    "therefore",
    "as a result"
  )

  /** Judgements of a value, including the labels that are never put on variability. */
  val evaluative: List[String] = List(
    "better",
    "worse",
    "good",
    "bad",
    "poor",
    "healthy",
    "unhealthy",
    "normal",
    "abnormal",
    "enough",
    "too little",
    "too much",
    "stable",
    "unstable",
    "volatile"
  )

  /** Advice and instructions. */
  val advice: List[String] = List(
    "should",
    "try",
    "consider",
    "need to",
    "must",
    "we recommend",
    "recommended",
    "aim for",
    "avoid"
  )

  /** Claims of statistical strength or prediction. */
  val statistical: List[String] = List(
    "significant",
    "significantly",
    "proven",
    "correlation",
    "correlated",
    "predicts",
    "risk",
    "likely",
    "effect",
    "impact"
  )

  /** Clinical vocabulary and condition names. */
  val clinical: List[String] = List(
    "diagnose",
    "diagnosis",
    "treat",
    "treatment",
    "symptom",
    "deficiency",
    "disorder",
    "insomnia",
    "dehydration",
    "dehydrated",
    "depressed",
    "depression",
    "anxious",
    "anxiety",
    "manic"
  )

  /** The same five lists for the Ukrainian catalogue (PRODUCT_SPEC R-LOC-8), in the word forms a sentence would use. A
    * form that the fixed closing sentences need ("причину" in "не може показати причину") is left out, as "cause" is in
    * English.
    */
  val ukrainian: List[String] = List(
    // causal
    "спричиняє",
    "спричинило",
    "спричинив",
    "призводить",
    "призвело",
    "допомагає",
    "допомогло",
    "шкодить",
    "покращує",
    "покращило",
    "погіршує",
    "погіршило",
    "впливає",
    "вплинуло",
    "тому що",
    "через це",
    "завдяки",
    "отже",
    "внаслідок",
    "тож",
    "бо",
    // evaluative
    "краще",
    "гірше",
    "кращий",
    "гірший",
    "здоровий",
    "нездоровий",
    "нормально",
    "нормальний",
    "ненормальний",
    "достатньо",
    "забагато",
    "стабільний",
    "нестабільний",
    // advice
    "слід",
    "варто",
    "спробуйте",
    "мусите",
    "потрібно більше",
    "рекомендуємо",
    "рекомендовано",
    "уникайте",
    // statistical
    "значущий",
    "значуще",
    "доведено",
    "кореляція",
    "корелює",
    "передбачає",
    "ризик",
    "імовірно",
    "ефект",
    // clinical
    "діагноз",
    "діагностувати",
    "лікування",
    "лікувати",
    "симптом",
    "дефіцит",
    "розлад",
    "безсоння",
    "зневоднення",
    "депресія",
    "тривожність"
  )

  val all: List[String] = causal ++ evaluative ++ advice ++ statistical ++ clinical ++ ukrainian

  // Unicode classes, so that a word boundary is also found between Cyrillic letters.
  private val patterns: List[(String, Pattern)] =
    all.map(phrase => phrase -> Pattern.compile(s"\\b${Pattern.quote(phrase)}\\b", Pattern.UNICODE_CHARACTER_CLASS))

  /** Every forbidden phrase found in `text`, together with the general list of [[ForbiddenPhrases]]. */
  def violations(text: String): List[String] =
    val lower = text.toLowerCase(Locale.ROOT)
    patterns.collect { case (phrase, pattern) if pattern.matcher(lower).find() => phrase } ++
      ForbiddenPhrases.violations(text)
end InsightPhrases
