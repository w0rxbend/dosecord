package dosegram.core.domain.imports

import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.Quality
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepTier

import java.time.Instant
import java.time.ZoneId

/** The staged form of an [[ImportRecord]]: JSON that the staging table holds sealed (V15). Written and read only by the
  * service, so a value that does not read back is a corrupt row.
  */
object RecordJson:

  def write(record: ImportRecord): String = ujson.write(value(record))

  def read(text: String): ImportRecord =
    val json = ujson.read(text)
    def at(key: String): Instant = Instant.parse(json(key).str)
    def zone(key: String): ZoneId = ZoneId.of(json(key).str)
    def step(key: String): Option[Int] = json.obj.get(key).flatMap(_.numOpt).map(_.toInt)
    json("type").str match
      case "water" => ImportRecord.Water(at("at"), zone("zone"), json("ml").num.toInt)
      case "mood"  =>
        ImportRecord.Mood(
          at("at"),
          zone("zone"),
          valid(Valence.of(json("valence").num.toInt)),
          step("energy").map(e => valid(Energy.of(e)))
        )
      case "sleep" =>
        val onset = SleepEdge(at("onset"), zone("onsetZone"), edge(json("onsetSource").str))
        val wake = SleepEdge(at("wake"), zone("wakeZone"), edge(json("wakeSource").str))
        ImportRecord.Sleep(
          SleepInterval(onset, wake),
          valid(SleepKind.values.find(_.dbValue == json("kind").str)),
          step("quality").map(q => valid(Quality.of(q))),
          valid(SleepTier.values.find(_.number == json("tier").num.toInt))
        )
      case "meal" =>
        ImportRecord.Meal(
          at("at"),
          zone("zone"),
          valid(MealType.fromDbValue(json("mealType").str)),
          json.obj.get("words").flatMap(_.strOpt).map(words => valid(MealText.of(words).toOption.flatten))
        )
      case other => throw IllegalStateException(s"a staged record of type '$other'")

  private def value(record: ImportRecord): ujson.Value = record match
    case ImportRecord.Water(at, zone, millilitres) =>
      ujson.Obj("type" -> "water", "at" -> at.toString, "zone" -> zone.getId, "ml" -> millilitres)
    case ImportRecord.Mood(at, zone, valence, energy) =>
      val json = ujson.Obj("type" -> "mood", "at" -> at.toString, "zone" -> zone.getId, "valence" -> valence.step)
      energy.foreach(e => json("energy") = e.step)
      json
    case ImportRecord.Sleep(interval, kind, quality, tier) =>
      val json = ujson.Obj(
        "type" -> "sleep",
        "onset" -> interval.onset.at.toString,
        "onsetZone" -> interval.onset.zone.getId,
        "onsetSource" -> interval.onset.source.dbValue,
        "wake" -> interval.wake.at.toString,
        "wakeZone" -> interval.wake.zone.getId,
        "wakeSource" -> interval.wake.source.dbValue,
        "kind" -> kind.dbValue,
        "tier" -> tier.number
      )
      quality.foreach(q => json("quality") = q.step)
      json
    case ImportRecord.Meal(at, zone, mealType, words) =>
      val json = ujson.Obj("type" -> "meal", "at" -> at.toString, "zone" -> zone.getId, "mealType" -> mealType.dbValue)
      words.foreach(w => json("words") = w.value)
      json

  private def edge(name: String): EdgeSource = valid(EdgeSource.values.find(_.dbValue == name))

  private def valid[A](value: Option[A]): A = value.getOrElse(throw IllegalStateException("a staged record is corrupt"))
end RecordJson
