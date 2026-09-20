package dosecord.core.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How a local wall time resolves against a zone's transitions (docs/DESIGN.md section 7.2, ADR-011): `Gap` for a local
  * time skipped by a forward transition, `Fold` for an ambiguous local time, `None` otherwise. The `dbValue` strings
  * match the `dst_kind` CHECK constraint.
  */
enum DstKind:
  case None, Gap, Fold

  def dbValue: String = toString.toLowerCase

object DstKind:
  def fromDbValue(value: String): Option[DstKind] =
    values.find(_.dbValue == value)

object Dst:

  /** Resolves a local wall time in `zone` to an instant, classifying the resolution. A gap shifts forward by the gap
    * length (the `ZonedDateTime.ofLocal` behaviour, matching zoneinfo `fold=0`); a fold picks the earlier instant (the
    * offset before the transition).
    */
  def resolveLocal(d: LocalDate, t: LocalTime, zone: ZoneId): (Instant, DstKind) =
    resolveLocal(LocalDateTime.of(d, t), zone)

  def resolveLocal(ldt: LocalDateTime, zone: ZoneId): (Instant, DstKind) =
    Option(zone.getRules.getTransition(ldt)) match
      case scala.None                           => (ldt.atZone(zone).toInstant, DstKind.None)
      case Some(transition) if transition.isGap =>
        (ZonedDateTime.ofLocal(ldt, zone, null).toInstant, DstKind.Gap)
      case Some(transition) =>
        (ZonedDateTime.ofLocal(ldt, zone, transition.getOffsetBefore).toInstant, DstKind.Fold)
