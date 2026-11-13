package dosegram.core.domain.input

import java.text.Normalizer
import java.util.Locale

/** Normalisation shared by every typed-input grammar (BOT_UX section 15.6): what a person types on any keyboard is read
  * the same as its plain ASCII spelling.
  */
object InputText:

  /** The longest piece of the user's own text an error message quotes back. */
  val MaxQuoted = 40

  /** NFKC (full-width digits and colons, no-break spaces), invisible format characters removed, every Unicode decimal
    * digit mapped to ASCII, lower case, runs of white space collapsed.
    */
  def normalise(text: String): String =
    plainForms(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim

  /** [[normalise]] without the change of case, for values whose spelling is kept (a dose unit is shown as typed).
    * Vulgar fractions become "n/d" first, because NFKC would glue "1½" into "11⁄2".
    */
  def plainForms(text: String): String =
    val fractions = VulgarFractions.foldLeft(text) { case (acc, (glyph, spelled)) => acc.replace(glyph, spelled) }
    Normalizer
      .normalize(fractions, Normalizer.Form.NFKC)
      .iterator
      .filterNot(c => Character.getType(c) == Character.FORMAT)
      .map(c => if Character.isDigit(c) then Character.forDigit(Character.digit(c, 10), 10) else c)
      .mkString
      .replace('⁄', '/')
      .replace('–', '-')
      .replace('—', '-')

  private val VulgarFractions: List[(String, String)] =
    List("½" -> " 1/2", "¼" -> " 1/4", "¾" -> " 3/4", "⅓" -> " 1/3", "⅔" -> " 2/3")

  /** Trailing sentence punctuation is not part of a value: "9:00." is "9:00". */
  def withoutTrailingPunctuation(text: String): String =
    text.replaceAll("[.!?…]+$", "").trim

  /** A piece of the user's text made safe to quote in a reply: control characters removed, white space collapsed, at
    * most [[MaxQuoted]] characters. The renderer escapes the markup; this only bounds what is echoed.
    */
  def quoted(text: String): String =
    val clean = text.iterator.filterNot(Character.isISOControl).mkString.replaceAll("\\s+", " ").trim
    if clean.length <= MaxQuoted then clean else clean.take(MaxQuoted - 1) + "…"
