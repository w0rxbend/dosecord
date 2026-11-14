package dosegram.core.domain.imports

import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepEdge
import dosegram.core.domain.sleep.SleepInterval
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepTier

import java.time.Instant
import java.time.ZoneId

/** A record of a health export as the Mini App normalises it on the device (PRODUCT_SPEC R-IMX-7, ROADMAP G10.7): the
  * raw file never leaves the device, only these. A device gives an instant with an offset but no zone, so the account's
  * zone fixes the local day (supervisor decision, 2026-11-14). Body mass is not here: it waits for the measurements
  * diary (ROADMAP G10.7, G12.1), and the Mini App counts it as ignored.
  */
enum DeviceRecord:
  case Water(at: Instant, millilitres: Int)

  /** A night or a nap the Mini App joined from the device's sleep samples: sensor sleep, tier 3. */
  case Sleep(onset: Instant, wake: Instant, kind: SleepKind)

  def normalised(zone: ZoneId, now: Instant): Either[ImportProblem, ImportRecord] = this match
    case Water(at, millilitres)   => ImportRecord.water(at, zone, millilitres, now)
    case Sleep(onset, wake, kind) =>
      val interval = SleepInterval(SleepEdge(onset, zone, EdgeSource.Sensor), SleepEdge(wake, zone, EdgeSource.Sensor))
      ImportRecord.sleep(interval, kind, quality = None, SleepTier.Sensor, now)
