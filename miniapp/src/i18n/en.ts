// English catalogue. Copy rules of MINI_APP 5.7: sentence case, a control says what it does,
// an error says what happened and what to do next, no clinical advice, no judgement words.
// Status words are those of BOT_UX 22.4 ("Not confirmed", "No record").
import type { DoseState } from '../api/types';

const litres = (ml: number): string => `${(ml / 1000).toFixed(1)} L`;

export const en = {
  app: { name: 'Dosegram' },

  tabs: { label: 'Main sections', today: 'Today', meds: 'Meds', insights: 'Insights' },

  gate: {
    outside: {
      title: 'Dosegram runs inside Telegram.',
      body: 'Open the Dosegram bot in Telegram and start the app from there.',
    },
    oldClient: {
      title: 'This Telegram version is too old for Dosegram.',
      body: 'Update Telegram, then open Dosegram again.',
    },
    accountRequired: {
      title: 'Start Dosegram in chat first.',
      body: 'Setup takes about a minute and includes your consent choices. Then open the app again.',
    },
    sessionEnded: {
      title: 'This session has ended.',
      body: 'Close this window and open Dosegram from the chat.',
    },
    offline: {
      title: 'No connection.',
      body: 'Check the network, then try again.',
    },
    close: 'Close',
    tryAgain: 'Try again',
  },

  common: {
    tryAgain: 'Try again',
    reference: (id: string) => `Reference: ${id}`,
    unmatchedRoute: 'That link did not match a screen.',
    dismiss: 'Dismiss',
    loadingScreen: 'Loading the screen',
    screenFailed: 'Something went wrong on this screen',
    reloadScreen: 'Reload screen',
    undo: 'Undo',
    required: 'required',
    optional: 'optional',
    leave: {
      title: 'Leave without saving?',
      message: 'Your changes are not saved.',
      stay: 'Keep editing',
      leave: 'Discard',
    },
    showTable: 'Show as table',
    hideTable: 'Hide table',
  },

  errors: {
    network: 'No connection. Check the network, then try again.',
    timeout: 'The request took too long. Try again.',
    not_found: 'This item does not exist or was removed.',
    unauthorised: 'This session has ended. Close this window and open Dosegram from the chat.',
    version_conflict: 'This item was changed elsewhere. Reload it, then make your change again.',
    validation: 'Some values were not accepted. Check the marked fields.',
    server: 'Dosegram could not load this. Try again.',
    unavailable: 'This part of Dosegram is not available yet.',
    // MINI_APP "Consent needed": consent is given in chat only (ARCHITECTURE API-19).
    consent_required:
      'Medication tracking is off. Dosegram records medications only with your consent. Send /meds in the chat to turn it on.',
    notSaved: 'Not saved. Try again.',
  },

  today: {
    title: 'Today',
    loading: 'Loading today',
    previousDay: 'Previous day',
    nextDay: 'Next day',
    backToToday: 'Back to today',
    dueNow: 'Due now',
    later: 'Later',
    earlier: (count: number) => `Earlier (${count})`,
    showEarlier: 'Show earlier doses',
    hideEarlier: 'Hide earlier doses',
    empty: 'Nothing scheduled today.',
    emptyAction: 'Add a medication',
    planned: 'Planned doses. You can answer them on the day.',
    answerInChat: 'Answer reminders in the chat. This screen shows what is recorded.',
    taken: 'Taken',
    skip: 'Skip',
    snooze: 'Snooze',
    snoozeFor: (minutes: number) => `${minutes} min`,
    snoozeGroup: 'Snooze for',
    actionsFor: (name: string) => `Actions for ${name}`,
    markedTaken: (time: string) => `Marked taken at ${time}`,
    markedSkipped: 'Marked skipped',
    snoozedUntil: (time: string) => `Snoozed until ${time}`,
    alreadyTaken: (time: string, where: 'chat' | 'app') =>
      `Already marked taken at ${time} in ${where === 'chat' ? 'chat' : 'the app'}`,
    alreadyResolved: (where: 'chat' | 'app') => `Already answered in ${where === 'chat' ? 'chat' : 'the app'}`,
    water: 'Water',
    waterTotal: (totalMl: number, goalMl: number) =>
      `${litres(totalMl)} of ${litres(goalMl)} (${Math.round((totalMl / goalMl) * 100)}%)`,
    addWater: (ml: number) => `Add ${ml} ml`,
    waterAdded: (ml: number) => `Added ${ml} ml`,
    dayLine: 'Day line',
  },

  dose: {
    pending: (time: string) => `Later, ${time}`,
    due: (minutes: number) => `Due for ${minutes} min`,
    snoozed: (time: string) => `Snoozed until ${time}`,
    taken: (time: string) => `Taken ${time}`,
    taken_late: (time: string) => `Taken ${time}, late`,
    skipped: 'Skipped',
    missed: 'Not confirmed',
    unknown: 'No record',
  },

  meds: {
    title: 'Medications',
    loading: 'Loading medications',
    add: 'Add medication',
    search: 'Search by name',
    filter: 'Show',
    status: { active: 'Active', paused: 'Paused', archived: 'Archived' },
    count: (count: number) => (count === 1 ? '1 medication' : `${count} medications`),
    empty: 'No medications yet. Add the first one to get reminders.',
    noMatch: 'No medication matches.',
    clearSearch: 'Clear search',
    noneInFilter: 'No medications in this group.',
    archivedReadOnly: 'Archived medications keep their history and cannot be changed.',
    edit: (name: string) => `Edit ${name}`,
  },

  editor: {
    titleNew: 'New medication',
    titleEdit: 'Edit medication',
    loading: 'Loading the medication form',
    name: 'Name',
    form: 'Form',
    strength: 'Strength',
    strengthUnit: 'Strength unit',
    chooseUnit: 'Choose a unit',
    dose: 'Dose each time',
    doseUnit: 'Dose unit',
    less: 'Less',
    more: 'More',
    looks: 'How it looks',
    colour: 'Colour',
    shape: 'Shape',
    notes: 'Notes',
    notesCount: (used: number, max: number) => `${used} of ${max}`,
    showName: 'Show the name in reminders',
    showNameHelp: 'Off: reminders say "your 08:00 item".',
    save: 'Save medication',
    saved: 'Medication saved',
    disclaimer: 'These are your own records. Dosegram does not check doses or interactions.',
    doseChangeNote: 'The new dose applies to doses from now on. Past records keep the dose they had.',
    summaryTitle: (count: number) => (count === 1 ? 'Check 1 field' : `Check ${count} fields`),
  },

  fieldErrors: {
    name_required: 'Enter a name',
    name_too_long: 'Use at most 80 characters',
    name_taken: 'You already have a medication with this name. Open it or choose another name.',
    strength_invalid: 'Enter a number greater than 0, for example 500 or 2.5',
    strength_unit_required: 'Choose a unit for the strength',
    dose_required: 'Enter how much you take each time',
    dose_invalid: 'Enter a number greater than 0',
    notes_too_long: 'Use at most 500 characters',
    times_required: 'Add at least one time',
    time_duplicate: 'This time is already in the list',
    time_invalid: 'Enter a time such as 08:00',
    weekday_required: 'Choose at least one day',
    date_invalid: 'Enter a date such as 2026-11-12',
    end_before_start: 'The end date is before the start date',
    no_occurrences: 'This schedule has no doses. Check the dates and days.',
    schedule_exists: 'This medication already has a schedule. End it before you add another.',
  } as Readonly<Record<string, string>>,

  schedule: {
    title: (name: string) => `Schedule for ${name}`,
    heading: 'When do you take it?',
    patterns: {
      fixed_times: 'Every day',
      weekly: 'Certain days of the week',
      as_needed: 'Only when needed',
    },
    weekdayNames: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'],
    loading: 'Loading the schedule form',
    pattern: 'Pattern',
    weekdays: 'Days of the week',
    times: 'Times and dose',
    time: (index: number) => `Time ${index}`,
    dose: (index: number) => `Dose ${index}`,
    remove: (index: number) => `Remove time ${index}`,
    addTime: 'Add a time',
    dates: 'Dates',
    starts: 'Starts',
    ends: 'Ends',
    endsHelp: 'Leave empty for no end date.',
    previewTitle: 'Next 7 days',
    preview: (zone: string) => `Next 7 days, ${zone} time`,
    previewLoading: 'Updating the preview',
    previewInvalid: 'The preview appears when the fields above are complete.',
    more: (count: number) => `and ${count} more`,
    asNeeded: 'No reminders. Log a dose from Today or in chat.',
    save: 'Save schedule',
    saved: 'Schedule saved',
    disclaimer: 'Enter the plan you were given. Dosegram does not suggest doses or times.',
  },

  insights: {
    title: 'Insights',
    loading: 'Loading insights',
    range: 'Period',
    days: (count: number) => `${count} days`,
    vessel: 'Water today',
  },
};

const DOSE_TIMED = { pending: 'scheduledTime', snoozed: 'snoozedUntil', taken: 'resolvedTime', taken_late: 'resolvedTime' } as const;

/** The word for a dose state, with its time where the state has one (MINI_APP 5.4). */
export function doseStatusText(occurrence: {
  state: DoseState;
  scheduledTime: string;
  resolvedTime?: string;
  snoozedUntil?: string;
  dueForMinutes?: number;
}): string {
  const { state } = occurrence;
  if (state === 'due') return en.dose.due(occurrence.dueForMinutes ?? 0);
  if (state === 'skipped' || state === 'missed' || state === 'unknown') return en.dose[state];
  return en.dose[state](occurrence[DOSE_TIMED[state]] ?? occurrence.scheduledTime);
}

export function errorText(code: string): string {
  return (en.errors as Readonly<Record<string, string>>)[code] ?? en.errors.server;
}

export function fieldErrorText(code: string): string {
  return en.fieldErrors[code] ?? en.errors.validation;
}
