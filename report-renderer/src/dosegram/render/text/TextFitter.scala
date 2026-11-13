package dosegram.render.text

import dosegram.render.tokens.TextStyle

/** Text that fits its slot: one or two lines and the style they must be drawn in. */
final case class FittedText(lines: Vector[String], style: TextStyle)

/** Prepares every string that enters a scene: sanitises it (VA-L10N-06), replaces characters the font pack cannot draw
  * (research brief 04 section 7.4), and fits it to a slot in the order of VA-TY-04: condensed width, then a second
  * line, then an ellipsis. The font size is never reduced.
  */
final class TextFitter(measurer: TextMeasurer):

  /** One line: as written if it fits, else condensed, else condensed with an ellipsis. */
  def line(raw: String, style: TextStyle, maxWidth: Double): FittedText =
    val text = TextFitter.drawable(raw, style)
    if measurer.width(text, style) <= maxWidth then FittedText(Vector(text), style)
    else
      val condensed = TextFitter.drawable(raw, style.condensed)
      FittedText(Vector(truncated(condensed, style.condensed, maxWidth)), style.condensed)

  /** Up to two lines, for user-entered names. */
  def twoLines(raw: String, style: TextStyle, maxWidth: Double): FittedText =
    val text = TextFitter.drawable(raw, style)
    val condensedStyle = style.condensed
    val condensed = TextFitter.drawable(raw, condensedStyle)
    if measurer.width(text, style) <= maxWidth then FittedText(Vector(text), style)
    else if measurer.width(condensed, condensedStyle) <= maxWidth then FittedText(Vector(condensed), condensedStyle)
    else
      val first = longestPrefix(condensed, condensedStyle, maxWidth)
      val rest = condensed.drop(first.length).trim
      FittedText(Vector(first.trim, truncated(rest, condensedStyle, maxWidth)), condensedStyle)

  def width(text: String, style: TextStyle): Double = measurer.width(text, style)

  private def truncated(text: String, style: TextStyle, maxWidth: Double): String =
    if measurer.width(text, style) <= maxWidth then text
    else
      Iterator
        .iterate(text)(_.dropRight(1))
        .takeWhile(_.nonEmpty)
        .map(_.trim + TextFitter.Ellipsis)
        .find(candidate => measurer.width(candidate, style) <= maxWidth)
        .getOrElse(TextFitter.Ellipsis)

  // Breaks at the last space that fits; a single word wider than the slot is broken inside the word.
  private def longestPrefix(text: String, style: TextStyle, maxWidth: Double): String =
    val fitting = text.inits.find(prefix => measurer.width(prefix, style) <= maxWidth).getOrElse("")
    val lastSpace = fitting.lastIndexOf(' ')
    if fitting.length == text.length || lastSpace <= 0 then fitting else fitting.take(lastSpace + 1)

object TextFitter:
  private val Ellipsis = "…"
  private val Replacement = "\ufffd"

  /** Sanitised text in which every character has a glyph in the face of `style`. A run of characters the font pack
    * cannot draw (an emoji, a word in an unsupported script) becomes one U+FFFD.
    */
  def drawable(raw: String, style: TextStyle): String =
    sanitised(raw).codePoints.toArray.iterator
      .map(codePoint =>
        if TableTextMeasurer.covers(codePoint, style) then Character.toString(codePoint) else Replacement
      )
      .mkString
      .replaceAll(s"$Replacement{2,}", Replacement)

  /** Removes control and format characters, including the bidirectional overrides and isolates, so entered text cannot
    * reorder or break its neighbours; white space of any kind becomes one space.
    */
  def sanitised(raw: String): String =
    raw.codePoints.toArray.iterator
      .map(codePoint =>
        if Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) then ' '.toInt else codePoint
      )
      .filterNot(isInvisible)
      .map(Character.toString)
      .mkString
      .replaceAll(" {2,}", " ")
      .trim

  private val InvisibleTypes: Set[Int] =
    Set(Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED)
      .map(_.toInt)

  private def isInvisible(codePoint: Int): Boolean = InvisibleTypes(Character.getType(codePoint))
