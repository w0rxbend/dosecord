package dosecord.core.domain.copy

import java.util.Locale

/** The forbidden-phrase list (ROADMAP M1.4a, R46): clinical advice and shaming must never appear in user-facing copy.
  * The bot records user actions; it never prescribes or advises doses, and skipped or missed doses are stated as fact,
  * never as failure. Seeded from the "Avoid" examples of docs/MEDICATION_REMINDER_UX.md and generalised.
  *
  * Matching is a case-insensitive substring check; phrases must therefore be specific enough not to ban neutral copy
  * (e.g. "take it" alone would hit the missed notice's "[I took it]" label).
  */
object ForbiddenPhrases:

  /** Dosing instructions or advice — always out of scope for the bot. */
  val clinicalAdvice: List[String] = List(
    "you should take",
    "you should skip",
    "take it now",
    "take it anyway",
    "take a double",
    "double your next dose",
    "double the dose",
    "double dose",
    "take an extra",
    "extra dose",
    "take two",
    "make up for the missed",
    "skip your next dose"
  )

  /** Shaming, judgement or pressure — adherence is reported, never moralised. */
  val shaming: List[String] = List(
    "you failed",
    "failure",
    "non-compliant",
    "noncompliant",
    "bad adherence",
    "you forgot again",
    "missed it again",
    "missed again",
    "your fault",
    "shame",
    "lazy"
  )

  val all: List[String] = clinicalAdvice ++ shaming

  /** Every forbidden phrase found in `text` (case-insensitive). */
  def violations(text: String): List[String] =
    val lower = text.toLowerCase(Locale.ROOT)
    all.filter(lower.contains)
end ForbiddenPhrases
