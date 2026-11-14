package dosegram.core.domain.imports

import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepTier

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import scala.util.Try

/** The Dosegram CSV template of one domain (ROADMAP G10.5, PRODUCT_SPEC R-IMX-13). Its columns are columns of the table
  * the export writes for the domain (`csv/<table>.csv`), so an exported file imports unchanged; the template names the
  * ones a person fills in. The doses have no template: an import creates no dose action (R-IMX-11).
  */
enum CsvTemplate(val kind: String, val table: String, val columns: List[String], val required: List[String]):
  case Water
      extends CsvTemplate(
        "water",
        "hydration_entries",
        List("occurred_at", "amount_ml"),
        List("occurred_at", "amount_ml")
      )
  case Mood
      extends CsvTemplate(
        "mood",
        "mood_checkins",
        List("occurred_at", "valence", "energy"),
        List("occurred_at", "valence")
      )
  case Sleep
      extends CsvTemplate(
        "sleep",
        "sleep_episodes",
        List("onset_at", "wake_at", "kind", "quality"),
        List("onset_at", "wake_at")
      )
  case Meals
      extends CsvTemplate(
        "meals",
        "meals",
        List("occurred_at", "meal_type", "description"),
        List("occurred_at", "meal_type")
      )

  /** The file name of the empty template; no health term beyond the domain's own word. */
  def fileName: String = s"dosegram-template-$kind.csv"

  /** The template: the header row only, so importing it unchanged imports nothing. */
  def content: String = columns.mkString("", ",", "\r\n")

object CsvTemplate:
  val Mime: String = "text/csv; charset=utf-8"

  def ofKind(kind: String): Option[CsvTemplate] = values.find(_.kind == kind)

  /** The template whose required columns the header holds; the meal type is checked first, because the export of the
    * meals has an `occurred_at` like the water and the mood.
    */
  def detect(header: Seq[String]): Option[CsvTemplate] =
    val names = header.map(_.trim.toLowerCase).toSet
    List(Sleep, Meals, Mood, Water).find(_.required.forall(names))

/** What one data row of a CSV file became. `line` is the line of the file the row starts on. */
enum RowOutcome:
  case Parsed(line: Int, record: ImportRecord, noOffset: Boolean)

  /** A voided entry of an exported file: it left every total, so it is not imported (supervisor decision, 2026-11-14).
    */
  case Skipped(line: Int)
  case Refused(line: Int, field: String, problem: ImportProblem)

/** Why a CSV file as a whole cannot be read. */
enum CsvRefusal:
  case Unreadable
  case UnknownColumns
  case TooManyRows

/** Reads a Dosegram CSV file, or one batch of it, into normalised records (PRODUCT_SPEC R-IMX-12, R-IMX-13). A time
  * with an offset is that instant; a time without one is read in the row's `tz` or, without it, the account's zone, and
  * is flagged. `firstLine` is the line of the original file that the batch's first data row is on: the Mini App sends a
  * large file in batches, each with the header, and the preview names lines of the file.
  */
object DosegramCsv:

  final case class Parsed(template: CsvTemplate, outcomes: List[RowOutcome])

  def read(text: String, accountZone: ZoneId, now: Instant, firstLine: Int = 2): Either[CsvRefusal, Parsed] =
    for
      rows <- CsvReader.rows(text).toRight(CsvRefusal.Unreadable)
      header <- rows.headOption.toRight(CsvRefusal.UnknownColumns)
      template <- CsvTemplate.detect(header.fields).toRight(CsvRefusal.UnknownColumns)
      data = rows.tail
      _ <- Either.cond(data.size <= ImportBounds.MaxBatchRecords, (), CsvRefusal.TooManyRows)
    yield
      val columns = header.fields.map(_.trim.toLowerCase).zipWithIndex.toMap
      val shift = firstLine - header.line - 1
      Parsed(template, data.map(row => outcome(template, Row(columns, row, shift), accountZone, now)))

  /** One data row with its cells by column name. */
  private final case class Row(columns: Map[String, Int], row: CsvRow, shift: Int):
    def line: Int = row.line + shift
    def cell(name: String): Option[String] =
      columns.get(name).flatMap(row.fields.lift).map(_.trim).filter(_.nonEmpty)

  private final case class Refusal(field: String, problem: ImportProblem)

  private def outcome(template: CsvTemplate, row: Row, accountZone: ZoneId, now: Instant): RowOutcome =
    if row.cell("voided_at").isDefined then RowOutcome.Skipped(row.line)
    else
      val parsed = for
        zone <- zoneOf(row, "tz", accountZone)
        record <- template match
          case CsvTemplate.Water => water(row, zone, now)
          case CsvTemplate.Mood  => mood(row, zone, now)
          case CsvTemplate.Sleep => sleep(row, zone, now)
          case CsvTemplate.Meals => meal(row, zone, now)
      yield record
      parsed match
        case Right((record, noOffset))     => RowOutcome.Parsed(row.line, record, noOffset)
        case Left(Refusal(field, problem)) => RowOutcome.Refused(row.line, field, problem)

  private type Read[A] = Either[Refusal, A]

  private def water(row: Row, zone: ZoneId, now: Instant): Read[(ImportRecord, Boolean)] =
    for
      (at, noOffset) <- time(row, "occurred_at", zone)
      millilitres <- int(row, "amount_ml")
      record <- ImportRecord.water(at, zone, millilitres, now).left.map(Refusal("amount_ml", _))
    yield (record, noOffset)

  private def mood(row: Row, zone: ZoneId, now: Instant): Read[(ImportRecord, Boolean)] =
    for
      (at, noOffset) <- time(row, "occurred_at", zone)
      valence <- int(row, "valence")
      energy <- optionalInt(row, "energy")
      record <- ImportRecord.mood(at, zone, valence, energy, now).left.map(Refusal("valence", _))
    yield (record, noOffset)

  private def meal(row: Row, zone: ZoneId, now: Instant): Read[(ImportRecord, Boolean)] =
    for
      (at, noOffset) <- time(row, "occurred_at", zone)
      text <- row.cell("meal_type").toRight(Refusal("meal_type", ImportProblem.MissingField))
      mealType <- MealType.fromDbValue(text.toLowerCase).toRight(Refusal("meal_type", ImportProblem.InvalidKind))
      record <- ImportRecord
        .meal(at, zone, mealType, row.cell("description").getOrElse(""), now)
        .left
        .map(Refusal("description", _))
    yield (record, noOffset)

  /** A night or a nap. An exported row keeps its tier and the source of each edge; a typed row is the user's own entry,
    * tier 2 with entered edges.
    */
  private def sleep(row: Row, wakeZone: ZoneId, now: Instant): Read[(ImportRecord, Boolean)] =
    for
      onsetZone <- zoneOf(row, "tz_onset", wakeZone)
      (onset, onsetNoOffset) <- time(row, "onset_at", onsetZone)
      (wake, wakeNoOffset) <- time(row, "wake_at", wakeZone)
      kind <- choice(row, "kind", SleepKind.values.toList, SleepKind.Main)(_.dbValue)
      tier <- choice(row, "tier", SleepTier.values.toList, SleepTier.Explicit)(_.number.toString)
      fallback = if tier == SleepTier.Sensor then EdgeSource.Sensor else EdgeSource.Retrospective
      onsetSource <- choice(row, "onset_source", EdgeSource.values.toList, fallback)(_.dbValue)
      wakeSource <- choice(row, "wake_source", EdgeSource.values.toList, fallback)(_.dbValue)
      quality <- optionalInt(row, "quality")
      interval = SleepInterval(SleepEdge(onset, onsetZone, onsetSource), SleepEdge(wake, wakeZone, wakeSource))
      record <- ImportRecord.sleep(interval, kind, quality, tier, now).left.map(Refusal("wake_at", _))
    yield (record, onsetNoOffset || wakeNoOffset)

  private def zoneOf(row: Row, column: String, fallback: ZoneId): Read[ZoneId] =
    row.cell(column) match
      case None       => Right(fallback)
      case Some(text) => Try(ZoneId.of(text)).toOption.toRight(Refusal(column, ImportProblem.UnknownZone))

  /** An instant with an offset (`2026-11-01T08:30:00+02:00`, `...Z`), or a local time read in `zone` and flagged. */
  private def time(row: Row, column: String, zone: ZoneId): Read[(Instant, Boolean)] =
    row.cell(column) match
      case None       => Left(Refusal(column, ImportProblem.MissingField))
      case Some(text) =>
        val iso = text.replaceFirst(" ", "T")
        Try(OffsetDateTime.parse(iso).toInstant)
          .map(_ -> false)
          .orElse(Try(LocalDateTime.parse(iso).atZone(zone).toInstant -> true))
          .toOption
          .toRight(Refusal(column, ImportProblem.InvalidTime))

  private def int(row: Row, column: String): Read[Int] =
    row.cell(column) match
      case None       => Left(Refusal(column, ImportProblem.MissingField))
      case Some(text) => wholeNumber(text).toRight(Refusal(column, ImportProblem.InvalidNumber))

  private def optionalInt(row: Row, column: String): Read[Option[Int]] =
    row.cell(column) match
      case None       => Right(None)
      case Some(text) => wholeNumber(text).map(Some(_)).toRight(Refusal(column, ImportProblem.InvalidNumber))

  /** `250` or `250.0`, as a spreadsheet may write a whole number; never a fraction. */
  private def wholeNumber(text: String): Option[Int] =
    text.toIntOption.orElse(
      Try(BigDecimal(text)).toOption.filter(_.isWhole).filter(_.isValidInt).map(_.toInt)
    )

  private def choice[A](row: Row, column: String, values: List[A], fallback: A)(name: A => String): Read[A] =
    row.cell(column) match
      case None       => Right(fallback)
      case Some(text) => values.find(name(_) == text.toLowerCase).toRight(Refusal(column, ImportProblem.InvalidKind))
end DosegramCsv
