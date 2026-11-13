// Synthetic seed data. Names are placeholders on purpose: no real medicine appears anywhere.
import type { Medication, MedicationOptions, MedicationSummary, ScheduleOptions } from '../api/types';

const choices = (values: readonly string[]) =>
  values.map((value) => ({ value, label: value.charAt(0).toUpperCase() + value.slice(1) }));

export const MEDICATION_OPTIONS: MedicationOptions = {
  forms: choices(['tablet', 'capsule', 'liquid', 'drops', 'injection', 'inhaler', 'spray', 'patch', 'other']),
  strengthUnits: [
    { value: 'mg', label: 'mg' },
    { value: 'mcg', label: 'mcg' },
    { value: 'g', label: 'g' },
    { value: 'ml', label: 'ml' },
    { value: 'IU', label: 'IU' },
    { value: 'mg/ml', label: 'mg/ml' },
  ],
  doseUnits: choices(['tablet', 'capsule', 'ml', 'drop', 'puff', 'spray', 'unit', 'patch']),
  colourTags: choices(['none', 'white', 'yellow', 'orange', 'pink', 'red', 'brown', 'green', 'blue', 'purple', 'grey']),
  shapeTags: choices(['none', 'round', 'oval', 'capsule', 'oblong', 'other']),
  limits: { nameMax: 80, notesMax: 500 },
};

export const SCHEDULE_PATTERNS: ScheduleOptions['patterns'] = [
  { value: 'fixed_times', label: 'Every day' },
  { value: 'weekly', label: 'Certain days of the week' },
  { value: 'as_needed', label: 'Only when needed' },
];

export const WEEKDAYS: ScheduleOptions['weekdays'] = [
  { value: 1, label: 'Mon' },
  { value: 2, label: 'Tue' },
  { value: 3, label: 'Wed' },
  { value: 4, label: 'Thu' },
  { value: 5, label: 'Fri' },
  { value: 6, label: 'Sat' },
  { value: 7, label: 'Sun' },
];

export interface MedicationRecord {
  medication: Medication;
  /** The lines the server would write for the registry row. */
  scheduleSummary: string;
  nextText?: string;
  lowStockText?: string;
}

function record(
  id: string,
  name: string,
  fields: Partial<Medication>,
  row: Omit<MedicationRecord, 'medication'>,
): MedicationRecord {
  return {
    medication: {
      id,
      name,
      status: 'active',
      version: 1,
      form: 'tablet',
      strengthValue: null,
      strengthUnit: null,
      doseAmount: '1',
      doseUnit: 'tablet',
      colourTag: 'none',
      shapeTag: 'none',
      notes: '',
      showNameInReminders: true,
      ...fields,
    },
    ...row,
  };
}

export function seedMedications(): MedicationRecord[] {
  return [
    record(
      'med-a',
      'Medication A',
      { strengthValue: '500', strengthUnit: 'mg', colourTag: 'white', shapeTag: 'round', notes: 'With breakfast' },
      { scheduleSummary: 'Every day at 08:00 and 21:00', nextText: 'Next at 21:00' },
    ),
    record(
      'med-b',
      'Medication B',
      { form: 'liquid', strengthValue: '5', strengthUnit: 'mg/ml', doseAmount: '2', doseUnit: 'ml' },
      { scheduleSummary: 'Mon, Wed and Fri at 13:00', nextText: 'Next on Fri at 13:00', lowStockText: '12 doses left' },
    ),
    record(
      'med-c',
      'Medication C',
      { form: 'capsule', doseUnit: 'capsule', colourTag: 'yellow', shapeTag: 'capsule' },
      { scheduleSummary: 'As needed', nextText: 'Last logged yesterday' },
    ),
    record('med-d', 'Medication D', { status: 'paused', colourTag: 'blue', shapeTag: 'oval' }, { scheduleSummary: 'Paused. Every day at 08:00' }),
    record('med-e', 'Medication E', { status: 'archived' }, { scheduleSummary: 'Archived' }),
  ];
}

export function toSummary({ medication, ...row }: MedicationRecord, hasDueOccurrence: boolean): MedicationSummary {
  const strength = medication.strengthValue === null ? '' : `${medication.strengthValue} ${medication.strengthUnit} `;
  return {
    id: medication.id,
    name: medication.name,
    status: medication.status,
    detailText: `${strength}${medication.form}, ${medication.doseAmount} ${medication.doseUnit} each time`,
    hasDueOccurrence,
    colourTag: medication.colourTag,
    shapeTag: medication.shapeTag,
    ...row,
  };
}

export interface OccurrenceSeed {
  readonly id: string;
  readonly medicationId: string;
  readonly medicationName: string;
  readonly doseText: string;
  readonly scheduledMinute: number;
  readonly resolution?: { readonly kind: 'taken' | 'skipped'; readonly atMinute: number };
}

export const OCCURRENCE_SEEDS: readonly OccurrenceSeed[] = [
  { id: 'occ-1', medicationId: 'med-c', medicationName: 'Medication C', doseText: '1 capsule', scheduledMinute: 450, resolution: { kind: 'taken', atMinute: 478 } },
  { id: 'occ-2', medicationId: 'med-d', medicationName: 'Medication D', doseText: '1 tablet', scheduledMinute: 480, resolution: { kind: 'skipped', atMinute: 481 } },
  { id: 'occ-3', medicationId: 'med-a', medicationName: 'Medication A', doseText: '1 tablet', scheduledMinute: 480 },
  { id: 'occ-4', medicationId: 'med-b', medicationName: 'Medication B', doseText: '2 ml', scheduledMinute: 780 },
  { id: 'occ-5', medicationId: 'med-a', medicationName: 'Medication A', doseText: '1 tablet', scheduledMinute: 1260 },
];

export const WATER_SEED: readonly { readonly minute: number; readonly ml: number }[] = [
  { minute: 435, ml: 500 },
  { minute: 500, ml: 700 },
];
export const HYDRATION_GOAL_ML = 2000;
export const HYDRATION_PRESETS_ML: readonly number[] = [150, 250, 500];
export const SNOOZE_CHOICES_MINUTES: readonly number[] = [10, 30, 60];
