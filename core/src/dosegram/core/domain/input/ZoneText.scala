package dosegram.core.domain.input

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/** A typed time zone (BOT_UX section 15.6): a zone id in any case ("europe/kyiv"), a city as the last part of a zone id
  * without case or diacritics ("Kyiv", "sao paulo", "new york"), or a UTC offset ("UTC+2", "GMT+02:00", "+5:30"). The
  * result is always an IANA zone id, never a bare offset (BUX-TZ-01): an offset becomes the first `preferred` zone that
  * has it at `now`, else the `Etc/GMT` zone of a whole hour. The text is read once and not kept (BUX-TZ-04).
  */
object ZoneText:

  private val Offset: Regex = "^(?:utc|gmt)? ?([+-]) ?(\\d{1,2})(?::?(\\d{2}))?$".r
  private val Universal = Set("utc", "gmt", "z", "utc+0", "utc-0", "gmt+0", "gmt-0")

  /** Region ids only: no `SystemV/` legacy ids and no fixed-offset ids. */
  private lazy val regionIds: List[String] =
    ZoneId.getAvailableZoneIds.asScala.toList.filterNot(id => id.startsWith("SystemV/")).sorted

  private lazy val byId: Map[String, String] = regionIds.map(id => key(id) -> id).toMap

  /** City key -> zone ids, the shortest id first (a link such as `America/Buenos_Aires` before its target). */
  private lazy val byCity: Map[String, List[String]] =
    regionIds
      .filter(_.contains('/'))
      .filterNot(_.startsWith("Etc/"))
      .groupBy(id => key(id.substring(id.lastIndexOf('/') + 1)))
      .view
      .mapValues(_.sortBy(id => (id.length, id)))
      .toMap

  def parse(text: String, now: Instant, preferred: List[ZoneId]): Option[ZoneId] =
    val typed = InputText.withoutTrailingPunctuation(InputText.normalise(text))
    if typed.isEmpty then None
    else if Universal.contains(typed.replace(" ", "")) then Some(ZoneId.of("UTC"))
    else
      byId
        .get(key(typed))
        .orElse(byCity.get(key(typed)).flatMap(_.headOption))
        .map(ZoneId.of)
        .orElse(offsetZone(typed.replace(" ", ""), now, preferred))

  private def offsetZone(typed: String, now: Instant, preferred: List[ZoneId]): Option[ZoneId] =
    typed match
      case Offset(sign, hours, minutes) =>
        val h = hours.toInt
        val m = Option(minutes).map(_.toInt).getOrElse(0)
        Option
          .when(h <= 14 && m <= 59):
            val seconds = (h * 3600 + m * 60) * (if sign == "-" then -1 else 1)
            ZoneOffset.ofTotalSeconds(seconds)
          .flatMap: offset =>
            preferred
              .find(_.getRules.getOffset(now) == offset)
              .orElse(etcZone(offset))
      case _ => None

  /** `Etc/GMT-3` is UTC+3: the Etc ids invert the sign. They exist for whole hours from UTC-12 to UTC+14. */
  private def etcZone(offset: ZoneOffset): Option[ZoneId] =
    val seconds = offset.getTotalSeconds
    if seconds == 0 then Some(ZoneId.of("UTC"))
    else if seconds % 3600 != 0 then None
    else
      val hours = seconds / 3600
      val id = if hours > 0 then s"Etc/GMT-$hours" else s"Etc/GMT+${-hours}"
      Option.when(regionIds.contains(id))(ZoneId.of(id))

  /** Lower case, no diacritics, `_` and `-` read as spaces. */
  private def key(text: String): String =
    Normalizer
      .normalize(text, Normalizer.Form.NFD)
      .replaceAll("\\p{M}", "")
      .toLowerCase(Locale.ROOT)
      .replace('_', ' ')
      .replace('-', ' ')
      .replaceAll("\\s+", " ")
      .trim
