// Between the generated OpenAPI types and the types the screens use. The server holds less than
// the screens can show (no form, strength or look of a medication; one dose per schedule), so
// the option resources tell the forms which fields exist, and nothing a person typed is dropped.
import { t as en } from '../i18n/index.svelte';
import type { FieldError } from './client';
import type { components } from './schema';
import type {
  AccountSettings,
  BlobAsset,
  CalendarSwatch,
  Choice,
  Chart,
  Consents,
  InventoryView,
  ExportItem,
  InsightCalendar,
  InsightList,
  InsightMonth,
  InsightRange,
  InsightsOverview,
  Meal,
  MealRecorded,
  MoodScale,
  MoodSummary,
  NotificationSettings,
  NutritionDay,
  NutritionSettings,
  NutritionSummary,
  Scene,
  SceneNode,
  SleepStatus,
  SleepSummary,
  DayProfile,
  ReminderPresets,
  Reminders,
  ReminderTimeline,
  WaterSettings,
  WaterSummary,
  Medication,
  MedicationDraft,
  MedicationOptions,
  MedicationSummary,
  Occurrence,
  Schedule,
  ScheduleDraft,
  ScheduleOptions,
  SchedulePreview,
  TodayView,
} from './types';

export type Wire = components['schemas'];

const ISO_WEEK = [1, 2, 3, 4, 5, 6, 7];

function minuteOf(time: string): number {
  const [hours = '0', minutes = '0'] = time.split(':');
  return Number(hours) * 60 + Number(minutes);
}

function toOccurrence(wire: Wire['DoseOccurrence'], nowMinute: number | undefined): Occurrence {
  const due = wire.state === 'due' && nowMinute !== undefined;
  return {
    id: wire.id,
    medicationName: wire.medicationName,
    doseText: wire.doseText ?? '',
    scheduledTime: wire.scheduledTime,
    state: wire.state,
    resolvedTime: wire.resolvedTime,
    snoozedUntil: wire.snoozedUntil,
    dueForMinutes: due ? Math.max(0, nowMinute - minuteOf(wire.scheduledTime)) : undefined,
    snoozeChoicesMinutes: [],
  };
}

/** The server has no action route yet, so a day is shown and doses are answered in the chat. */
export function toTodayView(wire: Wire['DayView']): TodayView {
  const occurrences = (wire.occurrences ?? []).map((occurrence) => toOccurrence(occurrence, wire.nowMinute));
  return {
    date: wire.date,
    dateLabel: wire.dateLabel,
    isToday: wire.isToday,
    readOnly: true,
    answerInChat: true,
    previousDate: wire.previousDate,
    nextDate: wire.nextDate,
    nowMinute: wire.nowMinute,
    occurrences,
    dayLine: occurrences.map((occurrence) => ({
      minute: minuteOf(occurrence.scheduledTime),
      kind: 'dose' as const,
      state: occurrence.state,
    })),
  };
}

export function toMedicationSummary(wire: Wire['MedicationSummary']): MedicationSummary {
  return {
    id: wire.id,
    name: wire.name,
    status: wire.status,
    detailText: wire.doseText ?? '',
    scheduleSummary: wire.scheduleSummary,
    hasDueOccurrence: false,
    colourTag: 'none',
    shapeTag: 'none',
  };
}

export function toMedication(wire: Wire['Medication']): Medication {
  return {
    id: wire.id,
    status: wire.status,
    version: wire.version,
    name: wire.name,
    form: 'other',
    strengthValue: null,
    strengthUnit: null,
    doseAmount: wire.doseAmount ?? '',
    doseUnit: wire.doseUnit ?? '',
    doseUnitLabel: wire.doseUnitLabel ?? wire.doseUnit ?? '',
    colourTag: 'none',
    shapeTag: 'none',
    notes: wire.instructions ?? '',
    showNameInReminders: true,
  };
}

/** Server validation entries, `member.code`, as the field and code the forms already show. */
const FIELD_ERRORS: Readonly<Record<string, FieldError>> = {
  'name.required': { field: 'name', code: 'name_required' },
  'name.invalid': { field: 'name', code: 'name_required' },
  'name.too_long': { field: 'name', code: 'name_too_long' },
  'name.duplicate': { field: 'name', code: 'name_taken' },
  'doseAmount.invalid': { field: 'doseAmount', code: 'dose_invalid' },
  'instructions.too_long': { field: 'notes', code: 'notes_too_long' },
  'times.required': { field: 'rows', code: 'times_required' },
  'times.invalid': { field: 'rows', code: 'time_invalid' },
  'times.duplicate': { field: 'rows', code: 'time_duplicate' },
  'endDate.before_start': { field: 'endDate', code: 'end_before_start' },
  'medication.schedule_exists': { field: 'schedule', code: 'schedule_exists' },
};

/** An entry without a mapping keeps its member and code; the form then shows the general text. */
export function toFieldError(wire: Wire['FieldError']): FieldError {
  return FIELD_ERRORS[`${wire.field}.${wire.code}`] ?? { field: wire.field, code: wire.code };
}

const present = (text: string): string | undefined => (text === '' ? undefined : text);

export function toMedicationInput(draft: MedicationDraft): Wire['MedicationInput'] {
  return {
    name: draft.name,
    doseAmount: present(draft.doseAmount),
    doseUnit: present(draft.doseUnit),
    instructions: present(draft.notes),
  };
}

/** The stored codes with their names in the account language; a server without names shows the codes. */
function doseUnitChoices(wire: Wire['MedicationOptions']): Choice[] {
  const named = wire.doseUnitChoices ?? [];
  if (named.length > 0) return named.map((choice) => ({ value: choice.code, label: choice.label }));
  return (wire.doseUnits ?? []).map((unit) => ({ value: unit, label: unit }));
}

/** Empty choice lists hide the fields the server does not keep. */
export function toMedicationOptions(wire: Wire['MedicationOptions']): MedicationOptions {
  return {
    forms: [],
    strengthUnits: [],
    doseUnits: doseUnitChoices(wire),
    colourTags: [],
    shapeTags: [],
    limits: { nameMax: wire.limits.medicationNameMax, notesMax: wire.limits.instructionsMax },
    reminderNameToggle: false,
  };
}

/** The server's `fixed_times` covers both "every day" and "certain days" of the builder. */
export function toScheduleOptions(wire: Wire['ScheduleOptions']): ScheduleOptions {
  const offered = wire.patterns ?? [];
  const patterns: ScheduleOptions['patterns'][number][] = [];
  if (offered.includes('fixed_times')) {
    patterns.push({ value: 'fixed_times', label: en.schedule.patterns.fixed_times });
    patterns.push({ value: 'weekly', label: en.schedule.patterns.weekly });
  }
  if (offered.includes('every_n_days')) patterns.push({ value: 'every_n_days', label: en.schedule.patterns.every_n_days });
  if (offered.includes('cycle')) patterns.push({ value: 'cycle', label: en.schedule.patterns.cycle });
  if (offered.includes('as_needed')) patterns.push({ value: 'as_needed', label: en.schedule.patterns.as_needed });
  return {
    patterns,
    weekdays: ISO_WEEK.map((day, index) => ({ value: day, label: en.schedule.weekdayNames[index] ?? '' })),
    maxRows: wire.timesMax,
    today: wire.today,
    perTimeDose: false,
  };
}

export function toScheduleDraft(draft: ScheduleDraft): Wire['ScheduleDraft'] {
  if (draft.pattern === 'as_needed')
    return { pattern: 'as_needed', maxPerDay: draft.maxPerDay, minGapMinutes: draft.minGapMinutes };
  if (draft.pattern === 'every_n_days' || draft.pattern === 'cycle')
    return {
      pattern: draft.pattern,
      times: draft.rows.map((row) => row.time),
      everyDays: draft.everyDays,
      onDays: draft.onDays,
      offDays: draft.offDays,
      startDate: draft.startDate,
      endDate: draft.endDate ?? undefined,
    };
  return {
    pattern: 'fixed_times',
    times: draft.rows.map((row) => row.time),
    weekdays: draft.pattern === 'weekly' ? [...draft.weekdays] : [],
    startDate: draft.startDate,
    endDate: draft.endDate ?? undefined,
  };
}

export function toSchedulePreview(wire: Wire['SchedulePreview']): SchedulePreview {
  return {
    summary: wire.summary,
    zoneLabel: wire.timeZone,
    days: (wire.days ?? []).map((day) => ({
      date: day.date,
      label: day.label,
      occurrences: (day.occurrences ?? []).map((occurrence) => ({
        time: occurrence.time,
        doseText: '',
        note: occurrence.note,
      })),
    })),
    moreCount: 0,
  };
}

export function toSchedule(wire: Wire['Schedule']): Schedule {
  const weekdays = wire.weekdays ?? [];
  const everyDay = weekdays.length === ISO_WEEK.length;
  const timed = everyDay ? 'fixed_times' : 'weekly';
  const periodic = wire.pattern === 'every_n_days' || wire.pattern === 'cycle';
  return {
    id: wire.id,
    medicationId: wire.medicationId,
    status: wire.status,
    revision: wire.revision,
    summary: wire.summary,
    pattern: wire.pattern === 'as_needed' || periodic ? wire.pattern : timed,
    rows: (wire.times ?? []).map((time) => ({ time, dose: '' })),
    weekdays: everyDay || periodic ? [] : weekdays,
    startDate: wire.startDate,
    endDate: wire.endDate ?? null,
    everyDays: wire.everyDays,
    onDays: wire.onDays,
    offDays: wire.offDays,
    // The chat wizard's defaults (10 a day, no gap) are not limits the user set.
    maxPerDay: wire.maxPerDay === 10 && !wire.minGapMinutes ? undefined : wire.maxPerDay,
    minGapMinutes: wire.minGapMinutes || undefined,
  };
}

const RANGES: readonly InsightRange[] = [7, 30, 90];
const isRange = (days: number): days is InsightRange => (RANGES as readonly number[]).includes(days);

/** Sentences, today's vessel and the charts of the period, each a scene the server laid out. */
export function toInsightsOverview(wire: Wire['InsightsOverview'], asked: InsightRange): InsightsOverview {
  return {
    rangeDays: isRange(wire.rangeDays) ? wire.rangeDays : asked,
    rangeLabel: wire.rangeLabel,
    ranges: (wire.ranges ?? []).filter(isRange),
    blocks: (wire.blocks ?? []).map((block) => ({
      id: block.id,
      title: block.title,
      lines: block.lines ?? [],
      noDataText: block.noDataText,
      charts: (block.charts ?? []).map(toChart),
      vessel: block.vessel,
      comparison: block.comparison,
      spark: block.spark && toScene(block.spark),
      details: block.details,
      medications: block.medications?.map((row) => ({ ...row })),
    })),
    notice: wire.notice,
  };
}

export function toInsightMonth(wire: Wire['InsightMonth']): InsightMonth {
  return {
    month: wire.month,
    monthLabel: wire.monthLabel,
    previousMonth: wire.previousMonth,
    nextMonth: wire.nextMonth,
    reportable: wire.reportable,
    daysWithRecords: wire.daysWithRecords,
    summary: wire.summary,
    domains: (wire.domains ?? []).map((domain) => ({
      id: domain.id,
      title: domain.title,
      recordedDays: domain.recordedDays,
      meetsGate: domain.meetsGate,
      lines: domain.lines ?? [],
      gateText: domain.gateText,
    })),
    medications: (wire.medications ?? []).map((row) => ({ ...row })),
    notice: wire.notice,
  };
}

export function toInsightList(wire: Wire['InsightList']): InsightList {
  return {
    windowLabel: wire.windowLabel,
    crossDomain: wire.crossDomain,
    items: (wire.items ?? []).map((item) => ({ id: item.id, text: item.text, howWorkedOut: item.howWorkedOut ?? [] })),
    close: wire.close ?? [],
    notice: wire.notice,
  };
}

// ---------- charts: the server's scene nodes as the painter's primitives ----------

function toSceneNode(wire: Wire['SceneNode']): SceneNode | null {
  const { x, y, tone: fill } = wire;
  switch (wire.shape) {
    case 'rect':
      return {
        kind: 'rect',
        x,
        y,
        width: wire.width ?? 0,
        height: wire.height ?? 0,
        radius: wire.radius,
        fill,
        opacity: wire.opacity,
        outlined: wire.style === 'outlined',
        dashed: wire.style === 'dashed',
        mark: wire.mark,
      };
    case 'line':
      return { kind: 'line', x1: x, y1: y, x2: wire.x2 ?? x, y2: wire.y2 ?? y, stroke: fill, dashed: wire.style === 'dashed' };
    case 'circle':
      return { kind: 'circle', x, y, r: wire.radius ?? 3, fill, mark: wire.mark };
    case 'hit':
      return wire.mark === undefined
        ? null
        : { kind: 'hit', x, y, width: wire.width ?? 0, height: wire.height ?? 0, mark: wire.mark };
    case 'text':
      return { kind: 'text', x, y, text: wire.text ?? '', fill, anchor: wire.anchor };
  }
}

function toScene(wire: Wire['Scene']): Scene {
  return {
    width: wire.width,
    height: wire.height,
    nodes: (wire.nodes ?? []).map(toSceneNode).filter((node) => node !== null),
  };
}

export function toChart(wire: Wire['Chart']): Chart {
  return {
    id: wire.id,
    title: wire.title,
    summary: wire.summary,
    scene: toScene(wire.scene),
    table: { caption: wire.table.caption, columns: wire.table.columns ?? [], rows: wire.table.rows ?? [] },
    marks: wire.marks ?? [],
    markDates: wire.markDates,
  };
}

// ---------- hydration and mood ----------

export function toWaterSummary(wire: Wire['HydrationSummary']): WaterSummary {
  return {
    today: wire.today,
    presetsMl: wire.presetsMl ?? [],
    chart: wire.chart && toChart(wire.chart),
    noDataText: wire.noDataText,
  };
}

export function toWaterSettings(wire: Wire['HydrationSettings']): WaterSettings {
  return { presetsMl: wire.presetsMl ?? [], goalMl: wire.goalMl, limits: wire.limits, version: wire.version };
}

export function toMoodScale(wire: Wire['MoodScale']): MoodScale {
  return {
    valence: wire.valence ?? [],
    energy: wire.energy ?? [],
    blobRevision: wire.blobRevision,
    statement: wire.statement,
  };
}

export function toBlobAsset(wire: Wire['MoodBlobStates']): BlobAsset {
  return {
    revision: wire.revision,
    states: (wire.states ?? []).map((state) => ({
      ...state,
      outline: state.outline ?? [],
      gradient: state.gradient ?? [],
      ground: state.ground ?? [],
      face: state.face && {
        ...state.face,
        eyes: state.face.eyes ?? [],
        mouth: state.face.mouth ?? [],
        brows: state.face.brows ?? [],
      },
    })),
  };
}

export function toMoodSummary(wire: Wire['MoodSummary']): MoodSummary {
  return { today: wire.today, days: wire.days ?? [] };
}

// ---------- sleep ----------

export function toSleepStatus(wire: Wire['SleepStatusView']): SleepStatus {
  return { ...wire, needsBedtime: Boolean(wire.needsBedtime) };
}

export function toSleepSummary(wire: Wire['SleepSummary']): SleepSummary {
  return {
    status: toSleepStatus(wire.status),
    lines: wire.lines ?? [],
    days: wire.days ?? [],
    chart: wire.chart && toChart(wire.chart),
    noDataText: wire.noDataText,
  };
}

// ---------- food ----------

export function toMeal(wire: Wire['MealView']): Meal {
  return { ...wire };
}

export function toNutritionDay(wire: Wire['NutritionDay']): NutritionDay {
  return { ...wire, logged: wire.logged ?? [], notLogged: wire.notLogged ?? [] };
}

export function toMealRecorded(wire: Wire['MealRecordedView']): MealRecorded {
  return { meal: toMeal(wire.meal), day: toNutritionDay(wire.day) };
}

export function toNutritionSummary(wire: Wire['NutritionSummary']): NutritionSummary {
  return {
    today: toNutritionDay(wire.today),
    days: (wire.days ?? []).map(toNutritionDay),
    perMeal: wire.perMeal ?? [],
    text: wire.text,
  };
}

export function toNutritionSettings(wire: Wire['NutritionSettings']): NutritionSettings {
  return { planned: wire.planned ?? [], version: wire.version };
}

// ---------- calendar ----------

/** `hasNoRecord` is left out of the document when it is false. */
function toSwatch(wire: Wire['CalendarSwatch']): CalendarSwatch {
  return { state: wire.state, step: wire.step, blobState: wire.blobState, hasNoRecord: Boolean(wire.hasNoRecord) };
}

export function toCalendar(wire: Wire['InsightCalendar']): InsightCalendar {
  return {
    domain: wire.domain,
    month: wire.month,
    monthLabel: wire.monthLabel,
    previousMonth: wire.previousMonth,
    nextMonth: wire.nextMonth,
    steps: wire.steps,
    weekStart: wire.weekStart,
    days: (wire.days ?? []).map((day) => ({ date: day.date, name: day.name, value: day.value, swatch: toSwatch(day.swatch) })),
    legend: (wire.legend ?? []).map((entry) => ({ label: entry.label, swatch: toSwatch(entry.swatch) })),
    summary: wire.summary,
    domains: wire.domains ?? [],
  };
}

// ---------- settings and consent ----------

export function toAccountSettings(wire: Wire['SettingsView']): AccountSettings {
  return {
    language: wire.language,
    languages: (wire.languages ?? []).map((choice) => ({ value: choice.code, label: choice.label })),
    timeZone: wire.timeZone,
    imageTheme: wire.imageTheme,
    version: wire.version,
  };
}

export function toNotificationSettings(wire: Wire['NotificationSettingsView']): NotificationSettings {
  return { discreet: wire.discreet, quietHours: wire.quietHours, notes: wire.notes ?? [], version: wire.version };
}

export function toConsents(wire: Wire['ConsentList']): Consents {
  return {
    items: (wire.items ?? []).map(({ purpose, title, granted, text }) => ({ purpose, title, granted, text })),
    notes: wire.notes ?? [],
  };
}

// ---------- reminders (ADR-023) ----------

export function toDayProfile(wire: Wire['DayProfileView']): DayProfile {
  return { ...wire, workDays: wire.workDays ?? [] };
}

export function toReminders(wire: Wire['RemindersView']): Reminders {
  return { ...wire, kinds: wire.kinds ?? [], day: toDayProfile(wire.day) };
}

export function toReminderPresets(wire: Wire['ReminderPresets']): ReminderPresets {
  return { prompts: (wire.prompts ?? []).map((preset) => ({ ...preset, preview: preset.preview ?? [] })) };
}

export function toReminderTimeline(wire: Wire['ReminderTimeline']): ReminderTimeline {
  return { date: wire.date, entries: wire.entries ?? [] };
}

// ---------- exports (MINI_APP S-14) ----------

export function toExportItem(wire: Wire['ExportView']): ExportItem {
  return { ...wire };
}

export function toInventory(wire: Wire['InventoryDocument']): InventoryView {
  return {
    onHand: wire.onHand,
    perIntake: wire.perIntake,
    thresholdDays: wire.thresholdDays ?? null,
    thresholdUnits: wire.thresholdUnits ?? null,
    lastRefill: wire.lastRefill ?? null,
    daysOfSupply: wire.daysOfSupply ?? null,
    coversUntil: wire.coversUntil ?? null,
    belowThreshold: wire.belowThreshold,
    countMayBeOff: wire.countMayBeOff,
    summary: wire.summary,
    version: wire.version,
  };
}
