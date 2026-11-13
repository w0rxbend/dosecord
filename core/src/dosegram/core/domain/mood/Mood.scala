package dosegram.core.domain.mood

import dosegram.core.domain.EntrySource

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** The mandatory part of a check-in: a step from 1 (very unpleasant) to 5 (very pleasant), PRODUCT_SPEC R-MOO-1. */
opaque type Valence = Int

object Valence:
  val steps: List[Valence] = List(1, 2, 3, 4, 5)
  def of(step: Int): Option[Valence] = Option.when(step >= 1 && step <= 5)(step)
  extension (valence: Valence) def step: Int = valence

/** The optional second step of a check-in: 1 (very low) to 5 (very high), PRODUCT_SPEC R-MOO-2. */
opaque type Energy = Int

object Energy:
  val steps: List[Energy] = List(1, 2, 3, 4, 5)
  def of(step: Int): Option[Energy] = Option.when(step >= 1 && step <= 5)(step)
  extension (energy: Energy) def step: Int = energy

/** The seven levels a mean of valences is quantised to (VISUALS_AND_ANALYTICS section 4.2). A single check-in reaches
  * V1, V2, V4, V6 and V7; V3 and V5 exist only for averages.
  */
enum MoodLevel:
  case V1, V2, V3, V4, V5, V6, V7

object MoodLevel:

  /** `v = (m - 3) / 2`, level `min(7, floor((v + 1) * 3.5) + 1)` for a mean `m` on the stored 1 to 5 scale. */
  def ofMean(mean: Double): MoodLevel =
    require(mean >= 1.0 && mean <= 5.0, s"a mood mean lies in 1..5, got $mean")
    val valence = (mean - 3.0) / 2.0
    fromOrdinal(Math.min(7, Math.floor((valence + 1.0) * 3.5).toInt + 1) - 1)

object MoodBounds:
  val MaxTags: Int = 5
  val UndoWindow: Duration = Duration.ofMinutes(15)

/** One immutable mood check-in (ARCHITECTURE section 10.8). Energy is absent when it was not given: it is never treated
  * as the middle step (R-MOO-2). `tagIds` name rows of the account's own tag list, at most five. A note is not part of
  * this value: it is encrypted content and never enters a statistic (R-MOO-4).
  */
final case class MoodCheckin(
    id: UUID,
    accountId: UUID,
    occurredAt: Instant,
    localDate: LocalDate,
    zone: ZoneId,
    valence: Valence,
    energy: Option[Energy],
    tagIds: List[UUID],
    source: EntrySource,
    clientKey: Option[String],
    supersedesId: Option[UUID],
    createdAt: Instant,
    voidedAt: Option[Instant]
):
  def isLive: Boolean = voidedAt.isEmpty
