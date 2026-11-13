// What the screens work with. The wire types are generated from the backend's OpenAPI document
// (./schema.d.ts) and mapped to these in ./wire.ts.
// Conventions of MINI_APP 15.1: local dates YYYY-MM-DD, local times HH:MM, quantities as decimal
// strings, and every sentence about a schedule or a chart written by the server.

export type LocalDate = string;
export type LocalTime = string;
export type Decimal = string;

export type DoseState =
  | 'pending'
  | 'due'
  | 'snoozed'
  | 'taken'
  | 'taken_late'
  | 'skipped'
  | 'missed'
  | 'unknown';

export interface Occurrence {
  readonly id: string;
  readonly medicationName: string;
  readonly doseText: string;
  readonly scheduledTime: LocalTime;
  readonly state: DoseState;
  /** When the dose was recorded as taken. */
  readonly resolvedTime?: LocalTime;
  readonly snoozedUntil?: LocalTime;
  readonly dueForMinutes?: number;
  readonly snoozeChoicesMinutes: readonly number[];
}

export interface HydrationDay {
  readonly totalMl: number;
  readonly goalMl: number;
  readonly presetsMl: readonly number[];
}

export interface DayLineMark {
  readonly minute: number;
  readonly kind: 'dose' | 'water';
  readonly state?: DoseState;
}

export interface TodayView {
  readonly date: LocalDate;
  readonly dateLabel: string;
  readonly isToday: boolean;
  /** Future days list planned occurrences without actions (MA-TODAY-9). */
  readonly readOnly: boolean;
  /** Set when this backend records doses in the chat only; the day is shown, not answered. */
  readonly answerInChat?: boolean;
  readonly previousDate: LocalDate;
  readonly nextDate: LocalDate;
  readonly nowMinute?: number;
  readonly occurrences: readonly Occurrence[];
  readonly hydration?: HydrationDay;
  readonly dayLine: readonly DayLineMark[];
}

export type OccurrenceAction =
  | { readonly kind: 'taken' | 'skipped' | 'undo' }
  | { readonly kind: 'snoozed'; readonly minutes: number };

export interface OccurrenceActionResult {
  readonly occurrence: Occurrence;
  /** Set when the occurrence was resolved elsewhere first (MA-TODAY-3); not an error. */
  readonly alreadyResolvedIn?: 'chat' | 'app';
}

export type MedicationStatus = 'active' | 'paused' | 'archived';

export interface MedicationSummary {
  readonly id: string;
  readonly name: string;
  readonly status: MedicationStatus;
  readonly detailText: string;
  /** One line from the server; the client composes no schedule sentence (MA-REG-2). */
  readonly scheduleSummary: string;
  readonly nextText?: string;
  readonly lowStockText?: string;
  readonly hasDueOccurrence: boolean;
  readonly colourTag: string;
  readonly shapeTag: string;
}

export interface MedicationDraft {
  readonly name: string;
  readonly form: string;
  readonly strengthValue: Decimal | null;
  readonly strengthUnit: string | null;
  readonly doseAmount: Decimal;
  readonly doseUnit: string;
  readonly colourTag: string;
  readonly shapeTag: string;
  readonly notes: string;
  readonly showNameInReminders: boolean;
}

export interface Medication extends MedicationDraft {
  readonly id: string;
  readonly status: MedicationStatus;
  readonly version: number;
}

export interface Choice {
  readonly value: string;
  readonly label: string;
}

export interface MedicationOptions {
  readonly forms: readonly Choice[];
  readonly strengthUnits: readonly Choice[];
  readonly doseUnits: readonly Choice[];
  readonly colourTags: readonly Choice[];
  readonly shapeTags: readonly Choice[];
  readonly limits: { readonly nameMax: number; readonly notesMax: number };
  /** Whether a medication can hide its name in reminders; without it the switch is not shown. */
  readonly reminderNameToggle: boolean;
}

export type SchedulePattern = 'fixed_times' | 'weekly' | 'as_needed';

export interface TimeRow {
  readonly time: LocalTime;
  readonly dose: Decimal;
}

export interface ScheduleDraft {
  readonly pattern: SchedulePattern;
  readonly rows: readonly TimeRow[];
  /** ISO weekdays, 1 is Monday. Used by the weekly pattern. */
  readonly weekdays: readonly number[];
  readonly startDate: LocalDate;
  readonly endDate: LocalDate | null;
}

export interface ScheduleOptions {
  readonly patterns: readonly { readonly value: SchedulePattern; readonly label: string }[];
  readonly weekdays: readonly { readonly value: number; readonly label: string }[];
  readonly maxRows: number;
  readonly today: LocalDate;
  /** Whether each time carries its own dose; without it the dose is the medication's. */
  readonly perTimeDose: boolean;
}

export interface PreviewOccurrence {
  readonly time: LocalTime;
  readonly doseText: string;
  /** The server's sentence for a clock change or quiet hours; never computed here. */
  readonly note?: string;
}

export interface PreviewDay {
  readonly date: LocalDate;
  readonly label: string;
  readonly occurrences: readonly PreviewOccurrence[];
}

export interface SchedulePreview {
  readonly summary: string;
  readonly zoneLabel: string;
  readonly days: readonly PreviewDay[];
  readonly moreCount: number;
}

export interface Schedule extends ScheduleDraft {
  readonly id: string;
  readonly medicationId: string;
  readonly revision: number;
}

// Charts are scenes laid out by the server and painted by one client painter (MINI_APP 5.6).
// Colours are token names, never literal values.
export type SceneColour =
  | 'domain-medication'
  | 'domain-hydration'
  | 'recorded'
  | 'text'
  | 'text-muted'
  | 'separator';

export type SceneNode =
  | {
      readonly kind: 'rect';
      readonly x: number;
      readonly y: number;
      readonly width: number;
      readonly height: number;
      readonly radius?: number;
      readonly fill: SceneColour;
      readonly opacity?: number;
      readonly outlined?: boolean;
    }
  | {
      readonly kind: 'line';
      readonly x1: number;
      readonly y1: number;
      readonly x2: number;
      readonly y2: number;
      readonly stroke: SceneColour;
      readonly dashed?: boolean;
    }
  | {
      readonly kind: 'text';
      readonly x: number;
      readonly y: number;
      readonly text: string;
      readonly fill: SceneColour;
      readonly anchor?: 'start' | 'middle' | 'end';
    };

export interface Scene {
  readonly width: number;
  readonly height: number;
  readonly nodes: readonly SceneNode[];
}

export interface ChartTable {
  readonly caption: string;
  readonly columns: readonly string[];
  readonly rows: readonly (readonly string[])[];
}

export interface Chart {
  readonly title: string;
  /** One sentence generated from the same data; the chart's accessible name (MA-INS-5). */
  readonly summary: string;
  readonly scene: Scene;
  readonly table: ChartTable;
}

export interface InsightBlock {
  readonly id: 'medication' | 'hydration';
  readonly title: string;
  /** Headline, comparison and other lines, all in the server's words. */
  readonly lines: readonly string[];
  /** Set when the period has too little data; then there are no charts (MA-INS-4). */
  readonly noDataText?: string;
  readonly charts: readonly Chart[];
  readonly vessel?: { readonly valueMl: number; readonly goalMl: number; readonly label: string };
}

export type InsightRange = 7 | 30 | 90;

export interface InsightsOverview {
  readonly rangeDays: InsightRange;
  readonly rangeLabel: string;
  readonly blocks: readonly InsightBlock[];
}
