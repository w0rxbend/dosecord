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

  val all: List[String] = causal ++ evaluative ++ advice ++ statistical ++ clinical

  private val patterns: List[(String, Pattern)] =
    all.map(phrase => phrase -> Pattern.compile(s"\\b${Pattern.quote(phrase)}\\b"))

  /** Every forbidden phrase found in `text`, together with the general list of [[ForbiddenPhrases]]. */
  def violations(text: String): List[String] =
    val lower = text.toLowerCase(Locale.ROOT)
    patterns.collect { case (phrase, pattern) if pattern.matcher(lower).find() => phrase } ++
      ForbiddenPhrases.violations(text)
end InsightPhrases
