package dosegram.core.domain.insights

import java.time.LocalDate
import java.util.UUID

/** The domains that have a daily rollup. The `dbValue` strings match the `daily_rollups.domain` CHECK. */
enum RollupDomain(val dbValue: String):
  case Medication extends RollupDomain("medication")
  case Hydration extends RollupDomain("hydration")
  case Mood extends RollupDomain("mood")
  case Nutrition extends RollupDomain("nutrition")

object RollupDomain:
  def fromDbValue(value: String): RollupDomain =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown rollup domain '$value'"))

/** One derived row: the figures of one account, one local day and one domain (ARCHITECTURE INS-01). */
final case class RollupKey(accountId: UUID, localDate: LocalDate, domain: RollupDomain)

/** The closed metrics shape of each domain (ARCHITECTURE INS-05): counts and sums only, never a name, a note or other
  * free text.
  */
enum RollupMetrics:
  case Medication(day: MedicationDay)
  case Hydration(day: HydrationDay)
  case Mood(day: MoodDay)
  case Nutrition(day: MealDay)

  def domain: RollupDomain =
    this match
      case _: Medication => RollupDomain.Medication
      case _: Hydration  => RollupDomain.Hydration
      case _: Mood       => RollupDomain.Mood
      case _: Nutrition  => RollupDomain.Nutrition

/** The JSON form of [[RollupMetrics]] in `daily_rollups.metrics`. The shape is written out field by field so that a
  * change of a case class cannot silently change what is stored; [[Version]] moves with every change of a shape.
  */
object RollupMetricsJson:

  /** `daily_rollups.rollup_version`. A row of another version is ignored by readers and rebuilt. */
  val Version: Int = 1

  def encode(metrics: RollupMetrics): String =
    val json = metrics match
      case RollupMetrics.Medication(day) =>
        ujson.Obj("perMedication" -> ujson.Arr.from(day.perMedication.toList.sortBy(_._1.toString).map(medication)))
      case RollupMetrics.Hydration(day) =>
        ujson.Obj(
          "totalMl" -> ujson.Num(day.totalMl.toDouble),
          "entries" -> ujson.Num(day.entries.toDouble),
          "goalMl" -> day.goalMl.fold[ujson.Value](ujson.Null)(goal => ujson.Num(goal.toDouble))
        )
      case RollupMetrics.Mood(day) =>
        ujson.Obj(
          "n" -> ujson.Num(day.n.toDouble),
          "valenceSum" -> ujson.Num(day.valenceSum.toDouble),
          "valenceMin" -> ujson.Num(day.valenceMin.toDouble),
          "valenceMax" -> ujson.Num(day.valenceMax.toDouble),
          "energyN" -> ujson.Num(day.energyN.toDouble),
          "energySum" -> ujson.Num(day.energySum.toDouble)
        )
      case RollupMetrics.Nutrition(day) =>
        ujson.Obj(
          "meals" -> ujson.Num(day.meals.toDouble),
          "breakfast" -> ujson.Num(day.breakfast.toDouble),
          "lunch" -> ujson.Num(day.lunch.toDouble),
          "dinner" -> ujson.Num(day.dinner.toDouble),
          "snacks" -> ujson.Num(day.snacks.toDouble)
        )
    ujson.write(json)

  def decode(domain: RollupDomain, text: String): RollupMetrics =
    val json = ujson.read(text)
    domain match
      case RollupDomain.Medication =>
        RollupMetrics.Medication(MedicationDay(json("perMedication").arr.map(medication).toMap))
      case RollupDomain.Hydration =>
        val goal = json("goalMl")
        RollupMetrics.Hydration(
          HydrationDay(int(json, "totalMl"), int(json, "entries"), Option.unless(goal.isNull)(goal.num.toInt))
        )
      case RollupDomain.Mood =>
        RollupMetrics.Mood(
          MoodDay(
            n = int(json, "n"),
            valenceSum = int(json, "valenceSum"),
            valenceMin = int(json, "valenceMin"),
            valenceMax = int(json, "valenceMax"),
            energyN = int(json, "energyN"),
            energySum = int(json, "energySum")
          )
        )
      case RollupDomain.Nutrition =>
        RollupMetrics.Nutrition(
          MealDay(int(json, "breakfast"), int(json, "lunch"), int(json, "dinner"), int(json, "snacks"))
        )

  private def medication(entry: (UUID, DoseBuckets)): ujson.Value =
    val (id, buckets) = entry
    ujson.Obj(
      "medicationId" -> ujson.Str(id.toString),
      "takenOnTime" -> ujson.Num(buckets.takenOnTime.toDouble),
      "takenLate" -> ujson.Num(buckets.takenLate.toDouble),
      "skipped" -> ujson.Num(buckets.skipped.toDouble),
      "missed" -> ujson.Num(buckets.missed.toDouble),
      "unknown" -> ujson.Num(buckets.unknown.toDouble),
      "open" -> ujson.Num(buckets.open.toDouble),
      "asNeeded" -> ujson.Num(buckets.asNeeded.toDouble)
    )

  private def medication(json: ujson.Value): (UUID, DoseBuckets) =
    UUID.fromString(json("medicationId").str) -> DoseBuckets(
      takenOnTime = int(json, "takenOnTime"),
      takenLate = int(json, "takenLate"),
      skipped = int(json, "skipped"),
      missed = int(json, "missed"),
      unknown = int(json, "unknown"),
      open = int(json, "open"),
      asNeeded = int(json, "asNeeded")
    )

  private def int(json: ujson.Value, field: String): Int = json(field).num.toInt
end RollupMetricsJson
