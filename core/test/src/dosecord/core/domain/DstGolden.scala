package dosecord.core.domain

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.zone.ZoneOffsetTransition

import scala.jdk.CollectionConverters.*

final case class GoldenRow(zone: ZoneId, local: LocalDateTime, kind: DstKind, instant: Instant):
  def render: String = s"${zone.getId}|$local|${kind.dbValue}|$instant"

object GoldenRow:
  def parse(line: String): GoldenRow =
    line.split("\\|", -1) match
      case Array(zone, local, kind, instant) =>
        GoldenRow(
          ZoneId.of(zone),
          LocalDateTime.parse(local),
          DstKind
            .fromDbValue(kind)
            .getOrElse(throw IllegalArgumentException(s"unknown dst_kind: $kind")),
          Instant.parse(instant)
        )
      case _ => throw IllegalArgumentException(s"malformed golden row: $line")

object DstGolden:

  val Zones: List[ZoneId] = List(
    "Europe/Kyiv",
    "Europe/London",
    "America/New_York",
    "Australia/Lord_Howe",
    "Asia/Kolkata",
    "Pacific/Apia",
    "America/Sao_Paulo"
  ).map(ZoneId.of)

  val ResourcePath = "core/test/resources/dst-golden.txt"

  private val WindowStart = Instant.parse("2000-01-01T00:00:00Z")
  private val WindowEnd = Instant.parse("2031-01-01T00:00:00Z")

  private val PlainLocals: List[LocalDateTime] = List(
    LocalDateTime.of(2026, 1, 15, 8, 0),
    LocalDateTime.of(2026, 6, 15, 20, 30),
    LocalDateTime.of(2026, 12, 15, 23, 45)
  )

  /** Expectation derived straight from tzdb transition data, independent of `Dst.resolveLocal`: a gap shifts forward by
    * the gap length under the offset after the transition; a fold takes the earlier instant, the offset before it.
    */
  private def expected(local: LocalDateTime, zone: ZoneId): (DstKind, Instant) =
    Option(zone.getRules.getTransition(local)) match
      case scala.None                           => (DstKind.None, local.atZone(zone).toInstant)
      case Some(transition) if transition.isGap =>
        (DstKind.Gap, local.plus(transition.getDuration).toInstant(transition.getOffsetAfter))
      case Some(transition) =>
        (DstKind.Fold, local.toInstant(transition.getOffsetBefore))

  private def midTransition(transition: ZoneOffsetTransition): LocalDateTime =
    val half = transition.getDuration.abs.dividedBy(2)
    if transition.isGap then transition.getDateTimeBefore.plus(half)
    else transition.getDateTimeAfter.plus(half)

  private def transitions(zone: ZoneId): List[ZoneOffsetTransition] =
    val rules = zone.getRules
    // getTransitions holds only the explicit historical transitions; everything under a recurring
    // last rule (e.g. Kyiv post-1997) must be enumerated with nextTransition.
    val recent = Iterator
      .unfold(WindowStart)(instant =>
        Option(rules.nextTransition(instant))
          .filter(_.getInstant.isBefore(WindowEnd))
          .map(t => (t, t.getInstant))
      )
      .toList
    if recent.nonEmpty then recent else rules.getTransitions.asScala.takeRight(4).toList

  def rows: List[GoldenRow] =
    for
      zone <- Zones
      local <- PlainLocals ++ transitions(zone).map(midTransition)
      (kind, instant) = expected(local, zone)
    yield GoldenRow(zone, local, kind, instant)

  def content: String =
    val header = List(
      "# resolveLocal golden rows generated from java.time ZoneRules transitions; do not hand-edit",
      s"# tzdb=${TzdbVersion.runtimeVersion()} zones=${Zones.map(_.getId).mkString(",")}",
      s"# regenerate: ./mill core.test.runMain dosecord.core.domain.dstGoldenGenerate $ResourcePath"
    )
    (header ++ rows.map(_.render)).mkString("", "\n", "\n")
