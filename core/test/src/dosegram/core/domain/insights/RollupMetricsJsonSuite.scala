package dosegram.core.domain.insights

import org.scalacheck.Prop.forAll

/** ARCHITECTURE INS-01 and INS-05: a rollup is a pure function of its source rows, and its stored shape is closed:
  * numbers and a medication identifier, never a name or other text.
  */
class RollupMetricsJsonSuite extends munit.ScalaCheckSuite:

  import TrackingGens.*

  private val medicationKeys =
    Set("medicationId", "takenOnTime", "takenLate", "skipped", "missed", "unknown", "open", "asNeeded")

  property("medication metrics survive the stored form and hold only the closed set of numeric fields"):
    forAll(InsightGens.genRecords, InsightGens.genZone) { (records, zone) =>
      MedicationStats.byDay(records, zone).values.foreach { day =>
        val metrics = RollupMetrics.Medication(day)
        val stored = RollupMetricsJson.encode(metrics)
        assertEquals(RollupMetricsJson.decode(RollupDomain.Medication, stored), metrics)
        val json = ujson.read(stored)
        assertEquals(json.obj.keySet.toSet, Set("perMedication"))
        json("perMedication").arr.foreach { medication =>
          assertEquals(medication.obj.keySet.toSet, medicationKeys)
          (medicationKeys - "medicationId").foreach(key => assert(medication(key).numOpt.isDefined, clue(key)))
        }
      }
    }

  property("hydration metrics survive the stored form and hold only numbers"):
    forAll(genWaters, genGoals) { (entries, goals) =>
      HydrationStats.byDay(entries, goals).values.foreach { day =>
        val stored = RollupMetricsJson.encode(RollupMetrics.Hydration(day))
        assertEquals(RollupMetricsJson.decode(RollupDomain.Hydration, stored), RollupMetrics.Hydration(day))
        val json = ujson.read(stored)
        assertEquals(json.obj.keySet.toSet, Set("totalMl", "entries", "goalMl"))
        assert(json.obj.values.forall(value => value.numOpt.isDefined || value.isNull), clue(stored))
      }
    }

  property("mood metrics survive the stored form and hold only numbers"):
    forAll(genMoods) { checkins =>
      MoodStats.byDay(checkins).values.foreach { day =>
        val stored = RollupMetricsJson.encode(RollupMetrics.Mood(day))
        assertEquals(RollupMetricsJson.decode(RollupDomain.Mood, stored), RollupMetrics.Mood(day))
        val json = ujson.read(stored)
        assertEquals(json.obj.keySet.toSet, Set("n", "valenceSum", "valenceMin", "valenceMax", "energyN", "energySum"))
        assert(json.obj.values.forall(_.numOpt.isDefined), clue(stored))
      }
    }

  property("recomputing a day from the same rows gives the same rollup, whatever the order of the rows"):
    forAll(genWaters, genGoals, genMoods) { (entries, goals, checkins) =>
      assertEquals(HydrationStats.byDay(entries.reverse, goals), HydrationStats.byDay(entries, goals))
      assertEquals(MoodStats.byDay(checkins.reverse), MoodStats.byDay(checkins))
    }
end RollupMetricsJsonSuite
