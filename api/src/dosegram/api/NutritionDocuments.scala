package dosegram.api

import dosegram.api.ApiJson.ReadWriter
import dosegram.api.ApiJson.given
import sttp.tapir.Codec
import sttp.tapir.CodecFormat
import sttp.tapir.DecodeResult
import sttp.tapir.Schema

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The JSON documents of the meal resources (ARCHITECTURE section 9.5, MINI_APP section 7.8). A meal is a type, a time
// and the user's optional words. No document carries energy, a nutrient, a target or a photo in this wave, and every
// sentence is written here, by the server (MINI_APP section 15.1).

enum MealKind(val wire: String):
  case Breakfast extends MealKind("breakfast")
  case Lunch extends MealKind("lunch")
  case Dinner extends MealKind("dinner")
  case Snack extends MealKind("snack")

object MealKind:
  given ReadWriter[MealKind] = ApiJson.wireEnum(values, _.wire)
  given Schema[MealKind] = Schema.derivedEnumeration[MealKind](encode = Some(_.wire))

  /** `?mealType=` of `GET /meals/recent`. */
  given Codec[String, MealKind, CodecFormat.TextPlain] =
    Codec.string
      .mapDecode(name =>
        values.find(_.wire == name) match
          case Some(kind) => DecodeResult.Value(kind)
          case None       => DecodeResult.Mismatch("breakfast, lunch, dinner or snack", name)
      )(_.wire)
      .schema(summon[Schema[MealKind]])

/** One meal. `text` is the meal in words for a list row and its accessible name; `description` holds the user's words
  * and is returned only to their owner.
  */
final case class MealView(
    id: UUID,
    date: LocalDate,
    time: String,
    occurredAt: Instant,
    mealType: MealKind,
    label: String,
    text: String,
    description: Option[String] = None
) derives ReadWriter,
      Schema

final case class Meals(from: LocalDate, to: LocalDate, items: List[MealView]) derives ReadWriter, Schema

/** A new meal, or with `supersedes` a change of a recorded one (PRODUCT_SPEC R-NUT-10). An absent time is now; on a
  * change, the time of the meal it replaces.
  */
final case class MealEntryInput(
    mealType: MealKind,
    occurredAt: Option[Instant] = None,
    description: Option[String] = None,
    supersedes: Option[UUID] = None
) derives ReadWriter,
      Schema

/** One local day: its meals, and which planned main meals have one. A planned meal without an entry is "not logged",
  * never "skipped" (R-NUT-3). `planned` is 0 when the user plans none: the day is then a count, never a rate.
  */
final case class NutritionDay(
    date: LocalDate,
    label: String,
    text: String,
    meals: Int,
    snacks: Int,
    planned: Int,
    plannedLogged: Int,
    logged: List[MealKind],
    notLogged: List[MealKind]
) derives ReadWriter,
      Schema

/** The answer to a write: the meal and its day. */
final case class MealRecordedView(meal: MealView, day: NutritionDay) derives ReadWriter, Schema

/** Up to five distinct descriptions saved before for one meal type, most recent first (MINI_APP S-08 behaviour 2). */
final case class MealRecent(mealType: MealKind, descriptions: List[String]) derives ReadWriter, Schema

/** Per main meal, on how many days of the range it was logged (MA-FOOD-4: counts, no percentages). */
final case class NutritionMealDays(mealType: MealKind, label: String, days: Int) derives ReadWriter, Schema

/** Today, the days of a range and the weekly line in the server's words. */
final case class NutritionSummary(
    from: LocalDate,
    to: LocalDate,
    today: NutritionDay,
    days: List[NutritionDay],
    perMeal: List[NutritionMealDays],
    text: String
) derives ReadWriter,
      Schema

/** The main meals the user plans (VISUALS section 7.5). */
final case class NutritionSettings(version: Long, planned: List[MealKind]) derives ReadWriter, Schema

/** The editable set; snacks cannot be planned. */
final case class NutritionSettingsInput(planned: List[MealKind]) derives ReadWriter, Schema
