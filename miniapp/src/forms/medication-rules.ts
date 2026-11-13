// The rule table of the medication editor (MINI_APP 7.4). The error codes are the vocabulary
// shared with the server, which validates again and answers with the same codes.
import type { FieldError } from '../api/client';
import type { MedicationDraft } from '../api/types';
import { isPositive, parseDecimal } from './decimal';

/** What the form holds while the person types: quantities are still text. */
export interface MedicationForm {
  name: string;
  form: string;
  strengthValue: string;
  strengthUnit: string;
  doseAmount: string;
  doseUnit: string;
  colourTag: string;
  shapeTag: string;
  notes: string;
  showNameInReminders: boolean;
}

export type MedicationField = keyof MedicationForm;

export interface MedicationLimits {
  readonly nameMax: number;
  readonly notesMax: number;
}

const STRENGTH = { integerDigits: 6, fractionDigits: 3 };
const DOSE = { integerDigits: 4, fractionDigits: 3 };
const CONTROL_CHARACTERS = /\p{Cc}/u;

type Rule = (form: MedicationForm, limits: MedicationLimits) => string | null;

function nameRule(form: MedicationForm, limits: MedicationLimits): string | null {
  const name = form.name.trim();
  if (name === '' || CONTROL_CHARACTERS.test(name)) return 'name_required';
  return [...name].length > limits.nameMax ? 'name_too_long' : null;
}

function strengthRule(form: MedicationForm): string | null {
  if (form.strengthValue.trim() === '') return null;
  const value = parseDecimal(form.strengthValue, STRENGTH);
  return value !== null && isPositive(value) ? null : 'strength_invalid';
}

function strengthUnitRule(form: MedicationForm): string | null {
  return form.strengthValue.trim() !== '' && form.strengthUnit === '' ? 'strength_unit_required' : null;
}

function doseRule(form: MedicationForm): string | null {
  if (form.doseAmount.trim() === '') return 'dose_required';
  const value = parseDecimal(form.doseAmount, DOSE);
  return value !== null && isPositive(value) ? null : 'dose_invalid';
}

function notesRule(form: MedicationForm, limits: MedicationLimits): string | null {
  return [...form.notes].length > limits.notesMax ? 'notes_too_long' : null;
}

/** In the order of the fields on the page, so the first error is the first invalid field. */
const RULES: readonly (readonly [MedicationField, Rule])[] = [
  ['name', nameRule],
  ['strengthValue', strengthRule],
  ['strengthUnit', strengthUnitRule],
  ['doseAmount', doseRule],
  ['notes', notesRule],
];

export function validateMedication(form: MedicationForm, limits: MedicationLimits): FieldError[] {
  return RULES.flatMap(([field, rule]) => {
    const code = rule(form, limits);
    return code === null ? [] : [{ field, code }];
  });
}

/** Call only for a form that validateMedication accepts. */
export function toMedicationDraft(form: MedicationForm): MedicationDraft {
  const strengthValue = parseDecimal(form.strengthValue, STRENGTH);
  return {
    name: form.name.trim(),
    form: form.form,
    strengthValue,
    strengthUnit: strengthValue === null ? null : form.strengthUnit,
    doseAmount: parseDecimal(form.doseAmount, DOSE) ?? '0',
    doseUnit: form.doseUnit,
    colourTag: form.colourTag,
    shapeTag: form.shapeTag,
    notes: form.notes.trim(),
    showNameInReminders: form.showNameInReminders,
  };
}

export function toMedicationForm(draft: MedicationDraft): MedicationForm {
  return {
    name: draft.name,
    form: draft.form,
    strengthValue: draft.strengthValue ?? '',
    strengthUnit: draft.strengthUnit ?? '',
    doseAmount: draft.doseAmount,
    doseUnit: draft.doseUnit,
    colourTag: draft.colourTag,
    shapeTag: draft.shapeTag,
    notes: draft.notes,
    showNameInReminders: draft.showNameInReminders,
  };
}

export function emptyMedicationForm(): MedicationForm {
  return {
    name: '',
    form: 'other',
    strengthValue: '',
    strengthUnit: '',
    doseAmount: '1',
    doseUnit: 'tablet',
    colourTag: 'none',
    shapeTag: 'none',
    notes: '',
    showNameInReminders: true,
  };
}
