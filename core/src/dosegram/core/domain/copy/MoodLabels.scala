package dosegram.core.domain.copy

import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.MoodLevel
import dosegram.core.domain.mood.Valence

/** The one vocabulary of mood (PRODUCT_SPEC R-MOO-1, R-MOO-2; VISUALS_AND_ANALYTICS section 4.2). The five valence
  * labels and the five energy labels name captured steps on every surface. The two extra level names exist only for
  * averages that fall between captured steps. No label names a condition.
  */
object MoodLabels:

  private def valences: Vector[String] =
    Lang.current match
      case Lang.En => Vector("Very unpleasant", "Unpleasant", "Neutral", "Pleasant", "Very pleasant")
      case Lang.Uk => Vector("Дуже неприємно", "Неприємно", "Нейтрально", "Приємно", "Дуже приємно")
  private def energies: Vector[String] =
    Lang.current match
      case Lang.En => Vector("Very low", "Low", "Medium", "High", "Very high")
      case Lang.Uk => Vector("Дуже низька", "Низька", "Середня", "Висока", "Дуже висока")
  private def SlightlyUnpleasant: String = tr("Slightly unpleasant", "Трохи неприємно")
  private def SlightlyPleasant: String = tr("Slightly pleasant", "Трохи приємно")

  def valence(valence: Valence): String = valences(valence.step - 1)
  def energy(energy: Energy): String = energies(energy.step - 1)

  /** The name of an averaged state: the valence label where the level is a captured step. */
  def level(level: MoodLevel): String =
    level match
      case MoodLevel.V1 => valences(0)
      case MoodLevel.V2 => valences(1)
      case MoodLevel.V3 => SlightlyUnpleasant
      case MoodLevel.V4 => valences(2)
      case MoodLevel.V5 => SlightlyPleasant
      case MoodLevel.V6 => valences(3)
      case MoodLevel.V7 => valences(4)

  def entries: List[CopyEntry] =
    Valence.steps.map(step => CopyEntry(s"mood.valence.${step.step}", valence(step))) ++
      Energy.steps.map(step => CopyEntry(s"mood.energy.${step.step}", energy(step))) ++
      List(CopyEntry("mood.avg.v3", SlightlyUnpleasant), CopyEntry("mood.avg.v5", SlightlyPleasant))
end MoodLabels
