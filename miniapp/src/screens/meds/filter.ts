import type { MedicationStatus, MedicationSummary } from '../../api/types';

export type StatusCounts = Readonly<Record<MedicationStatus, number>>;

export function countByStatus(medications: readonly MedicationSummary[]): StatusCounts {
  const counts = { active: 0, paused: 0, archived: 0 };
  for (const medication of medications) counts[medication.status] += 1;
  return counts;
}

/**
 * The registry is loaded whole and filtered in memory, because names are encrypted at rest and
 * the server cannot search them (MINI_APP 7.2). The match ignores case and surrounding spaces.
 * Medications with a due dose come first; otherwise the server's order is kept.
 */
export function filterMedications(
  medications: readonly MedicationSummary[],
  status: MedicationStatus,
  query: string,
): MedicationSummary[] {
  const needle = query.trim().toLowerCase();
  return medications
    .filter((medication) => medication.status === status && medication.name.toLowerCase().includes(needle))
    .sort((a, b) => Number(b.hasDueOccurrence) - Number(a.hasDueOccurrence));
}
