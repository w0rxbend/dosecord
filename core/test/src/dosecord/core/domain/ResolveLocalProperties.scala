package dosecord.core.domain

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class ResolveLocalProperties extends munit.ScalaCheckSuite:

  private val localGen: Gen[LocalDateTime] =
    Gen
      .choose(946684800L, 1924991999L) // 2000-01-01 .. 2030-12-31 as epoch seconds
      .map(LocalDateTime.ofEpochSecond(_, 0, ZoneOffset.UTC))

  private val zoneGen: Gen[ZoneId] = Gen.oneOf(DstGolden.Zones)

  property("resolveLocal agrees with the tzdb transition data on every sampled wall time"):
    forAll(localGen, zoneGen) { (local, zone) =>
      val (instant, kind) = Dst.resolveLocal(local, zone)
      Option(zone.getRules.getTransition(local)) match
        case scala.None =>
          assertEquals(kind, DstKind.None)
          assertEquals(instant.atZone(zone).toLocalDateTime, local)
        case Some(transition) if transition.isGap =>
          assertEquals(kind, DstKind.Gap)
          assertEquals(instant, local.plus(transition.getDuration).toInstant(transition.getOffsetAfter))
        case Some(transition) =>
          assertEquals(kind, DstKind.Fold)
          assertEquals(instant, local.toInstant(transition.getOffsetBefore))
    }
