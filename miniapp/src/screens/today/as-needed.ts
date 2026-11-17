import type { ApiClient } from '../../api/client';
import type { MedicationSummary } from '../../api/types';

/** An as-needed medication with no occurrence today. Times are omitted: this sheet has no reminders (MA-SCHED-5). */
export interface AsNeededFact {
  readonly id: string;
  readonly name: string;
  readonly maxPerDay?: number;
  readonly minGapMinutes?: number;
}

/** Active as-needed schedules whose medication has no occurrence on the day being shown. */
export async function loadAsNeeded(api: ApiClient, namesWithDoses: readonly string[]): Promise<AsNeededFact[]> {
  const medications = await api.medications();
  const withoutDose = medications.filter((medication) => medication.status === 'active' && !namesWithDoses.includes(medication.name));
  const groups = await Promise.all(withoutDose.map((medication) => factsFor(api, medication)));
  return groups.flat();
}

async function factsFor(api: ApiClient, medication: MedicationSummary): Promise<AsNeededFact[]> {
  const schedules = await api.schedules(medication.id);
  return schedules
    .filter((schedule) => schedule.status === 'active' && schedule.pattern === 'as_needed')
    .map((schedule) => ({
      id: schedule.id,
      name: medication.name,
      maxPerDay: schedule.maxPerDay,
      minGapMinutes: schedule.minGapMinutes,
    }));
}
