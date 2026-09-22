package dosecord.core.application.mood

import dosecord.contracts.MoodLevel
import dosecord.core.domain.copy.MoodCopy

import java.util.Locale

/** `/mood <1-10|word> [note] [#tag ...]` parsing (ROADMAP M0.12d, R35/R37, C18). The parsing rules — the 1-10 range and
  * the keyword map — are salvaged from the retired prototype's `_parse_mood` (git history) and pinned as test fixtures
  * in `MoodParsingSuite`; this is a fresh implementation. The note is kept only when supplied; the trailing run of
  * `#tag` tokens lands in `tags` (R37); a `#tag` earlier in the text is note, not a tag.
  */
object MoodParsing:

  final case class ParsedMood(level: MoodLevel, note: Option[String], tags: List[String])

  /** The salvaged keyword map, word to level. */
  val keywords: Map[String, Int] = Map(
    "terrible" -> 1,
    "awful" -> 1,
    "bad" -> 2,
    "okay" -> 5,
    "fine" -> 5,
    "good" -> 8,
    "great" -> 10,
    "amazing" -> 10
  )

  /** Parses the argument text after `/mood`. `Left` is the catalogue usage error; nothing is stored. */
  def parse(args: String): Either[String, ParsedMood] =
    val tokens = args.trim.split("\\s+").toList.filter(_.nonEmpty)
    tokens match
      case Nil                => Left(MoodCopy.invalidMood)
      case levelToken :: rest =>
        levelOf(levelToken) match
          case None        => Left(MoodCopy.invalidMood)
          case Some(level) =>
            val (tagsRev, noteTokensRev) = rest.reverse.span(t => t.startsWith("#") && t.length > 1)
            val tags = tagsRev.reverse.map(_.drop(1)).distinct
            val note = Option(noteTokensRev.reverse.mkString(" ")).filter(_.nonEmpty)
            Right(ParsedMood(MoodLevel.unsafe(level), note, tags))

  /** A level is an integer in 1-10 or a salvaged keyword (case-insensitive). */
  def levelOf(token: String): Option[Int] =
    token.toIntOption
      .filter(level => level >= MoodLevel.Min && level <= MoodLevel.Max)
      .orElse(keywords.get(token.toLowerCase(Locale.ROOT)))
end MoodParsing
