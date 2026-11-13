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

export interface DayLineMark {
  readonly minute: number;
  readonly kind: 'dose' | 'water' | 'mood';
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
// Colours are token names, never literal values. `mark` ties a node to the chart's mark of the
// same index, whose exact value is in `Chart.marks` (MA-INS-6).
export type SceneColour =
  | 'domain-medication'
  | 'domain-hydration'
  | 'domain-mood'
  | 'domain-sleep'
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
      readonly dashed?: boolean;
      readonly mark?: number;
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
      readonly kind: 'circle';
      readonly x: number;
      readonly y: number;
      readonly r: number;
      readonly fill: SceneColour;
      readonly mark?: number;
    }
  | {
      /** An invisible area over one mark: what a tap selects. */
      readonly kind: 'hit';
      readonly x: number;
      readonly y: number;
      readonly width: number;
      readonly height: number;
      readonly mark: number;
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
  readonly id: string;
  readonly title: string;
  /** One sentence generated from the same data; the chart's accessible name (MA-INS-5). */
  readonly summary: string;
  readonly scene: Scene;
  readonly table: ChartTable;
  /** The exact value of each selectable mark, in reading order (MA-INS-6). */
  readonly marks: readonly string[];
}

export interface InsightBlock {
  readonly id: Domain;
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
  /** The periods the backend computes; the screen offers these and no other. */
  readonly ranges: readonly InsightRange[];
  /** One block per domain the account has turned on. */
  readonly blocks: readonly InsightBlock[];
  /** The server's sentence for an account with no block at all. */
  readonly notice?: string;
}

// ---------- tracking domains (MINI_APP 7.7, 7.9, 7.12) ----------
// Amounts are whole millilitres, the stored unit (MA-HYD-3). Every sentence is the server's.

export type Domain = 'medication' | 'hydration' | 'mood' | 'sleep';

/** A read of a domain whose consent is missing: the screen shows "turned off", not an error. */
export const OFF = 'off';
export type Off = typeof OFF;

export interface WaterDay {
  readonly date: LocalDate;
  readonly totalMl: number;
  readonly entries: number;
  readonly text: string;
  readonly goalMl?: number;
  readonly percent?: number;
  /** The vessel's accessible name: value, goal and percent (MA-HYD-2). */
  readonly vesselLabel?: string;
}

export interface WaterEntry {
  readonly id: string;
  readonly date: LocalDate;
  readonly time: LocalTime;
  readonly amountMl: number;
  readonly text: string;
}

export interface WaterLogged {
  readonly entry: WaterEntry;
  readonly day: WaterDay;
  /** True only for the entry that first takes the day to the goal (MA-HYD-5). */
  readonly goalReachedNow: boolean;
}

export interface WaterLimits {
  readonly entryMinMl: number;
  readonly entryMaxMl: number;
  readonly goalMinMl: number;
  readonly goalMaxMl: number;
  readonly presetsMax: number;
}

export interface WaterSettings {
  readonly presetsMl: readonly number[];
  readonly goalMl?: number;
  readonly limits: WaterLimits;
  readonly version: number;
}

export interface WaterSettingsInput {
  readonly presetsMl: readonly number[];
  readonly goalMl?: number;
}

export interface WaterSummary {
  readonly today: WaterDay;
  readonly presetsMl: readonly number[];
  readonly chart?: Chart;
  readonly noDataText?: string;
}

export interface MoodStop {
  readonly step: number;
  readonly label: string;
}

export interface MoodScale {
  readonly valence: readonly MoodStop[];
  readonly energy: readonly MoodStop[];
  readonly blobRevision: string;
  /** "Dosegram records how you feel. It does not assess it." (MA-MOOD-7) */
  readonly statement: string;
}

export interface BlobColours {
  readonly fillInner: string;
  readonly fillOuter: string;
  readonly outline: string;
  readonly ground: string;
  readonly background: string;
}

export interface BlobFace {
  readonly ink: string;
  readonly eyes: readonly (readonly number[])[];
  readonly mouth: readonly number[];
  readonly strokeWidth: number;
  readonly brows: readonly (readonly number[])[];
}

/** One drawn state in units of the blob radius; the geometry is the server's (MINI_APP 7.9). */
export interface BlobState {
  readonly id: string;
  readonly outline: readonly number[];
  readonly outlineWidth: number;
  readonly isDotted: boolean;
  readonly gradient: readonly number[];
  readonly ground: readonly number[];
  readonly light: BlobColours;
  readonly dark: BlobColours;
  readonly face?: BlobFace;
}

export interface BlobAsset {
  readonly revision: string;
  readonly states: readonly BlobState[];
}

export interface MoodCheckin {
  readonly id: string;
  readonly date: LocalDate;
  readonly time: LocalTime;
  readonly valence: number;
  readonly energy?: number;
  readonly label: string;
  readonly blobState: string;
}

export interface MoodCheckinInput {
  readonly valence: number;
  readonly energy?: number;
}

export interface MoodDay {
  readonly date: LocalDate;
  readonly checkIns: number;
  readonly text: string;
  readonly blobState: string;
  readonly latest?: MoodCheckin;
}

export interface MoodRecorded {
  readonly checkin: MoodCheckin;
  readonly day: MoodDay;
}

export interface MoodSummaryDay {
  readonly date: LocalDate;
  readonly label: string;
  /** The day in words, for the accessible name of its small blob. */
  readonly name: string;
  readonly blobState: string;
  readonly checkIns: number;
}

export interface MoodSummary {
  readonly today: MoodDay;
  readonly days: readonly MoodSummaryDay[];
}

// ---------- sleep (MINI_APP 7.10) ----------
// Every value carries its source in words (MA-SLEEP-1); no score and no reference duration (MA-SLEEP-3, MA-SLEEP-4).

export type SleepKind = 'night' | 'nap';

export interface SleepEpisode {
  readonly id: string;
  readonly kind: SleepKind;
  /** The sleep day: the local date on which the episode ended. */
  readonly date: LocalDate;
  readonly onsetAt: string;
  readonly wakeAt: string;
  readonly bedtime: LocalTime;
  readonly wakeTime: LocalTime;
  readonly minutes: number;
  readonly duration: string;
  readonly source: string;
  readonly text: string;
  readonly quality?: number;
}

export interface SleepEpisodeInput {
  readonly kind: SleepKind;
  /** Instants, ISO 8601 with offset. */
  readonly onsetAt: string;
  readonly wakeAt: string;
}

export type SleepCheckinKind = 'going_to_sleep' | 'awake';

export interface SleepStatus {
  readonly text: string;
  /** The clock time of an open "Going to sleep"; the primary button is then "I'm up". */
  readonly openSince?: LocalTime;
  readonly lastNight?: SleepEpisode;
  readonly goalMinutes?: number;
  readonly goalText?: string;
  /** "I'm up" found no open night: nothing is guessed, the night is asked (R-SLP-4). */
  readonly needsBedtime: boolean;
}

export interface SleepSettings {
  readonly goalMinutes?: number;
  readonly limits: { readonly goalMinMinutes: number; readonly goalMaxMinutes: number };
  readonly version: number;
}

export interface SleepSummaryDay {
  readonly date: LocalDate;
  readonly label: string;
  readonly name: string;
  readonly minutes?: number;
}

export interface SleepSummary {
  readonly status: SleepStatus;
  readonly lines: readonly string[];
  readonly days: readonly SleepSummaryDay[];
  readonly chart?: Chart;
  readonly noDataText?: string;
}

export interface CalendarSwatch {
  readonly state: 'value' | 'empty' | 'future';
  readonly step?: number;
  readonly hasNoRecord: boolean;
  readonly blobState?: string;
}

export interface CalendarDay {
  readonly date: LocalDate;
  readonly swatch: CalendarSwatch;
  /** The date and the value in words (MA-HIST-1). */
  readonly name: string;
  /** The short value printed in the cell on request (MA-HIST-3). */
  readonly value?: string;
}

export interface InsightCalendar {
  readonly domain: Domain;
  readonly month: string;
  readonly monthLabel: string;
  readonly previousMonth: string;
  readonly nextMonth?: string;
  readonly steps: number;
  readonly weekStart: number;
  readonly days: readonly CalendarDay[];
  readonly legend: readonly { readonly label: string; readonly swatch: CalendarSwatch }[];
  readonly summary: string;
  readonly domains: readonly Domain[];
}

// ---------- settings and consent (MINI_APP 7.15) ----------

export type ImageTheme = 'auto' | 'light' | 'dark';

export interface AccountSettings {
  readonly language: string;
  readonly languages: readonly Choice[];
  readonly timeZone: string;
  readonly imageTheme: ImageTheme;
  readonly version: number;
}

export interface AccountSettingsInput {
  readonly language: string;
  readonly timeZone: string;
  readonly imageTheme: ImageTheme;
}

export interface QuietHours {
  readonly start: LocalTime;
  readonly end: LocalTime;
}

export interface NotificationSettings {
  readonly discreet: boolean;
  readonly quietHours?: QuietHours;
  /** What each setting does today, in the server's words. */
  readonly notes: readonly string[];
  readonly version: number;
}

export interface NotificationSettingsInput {
  readonly discreet: boolean;
  readonly quietHours?: QuietHours;
}

export interface TimeZoneChoice {
  readonly id: string;
  readonly offset: string;
}

export interface TimeZonePreview {
  readonly timeZone: string;
  readonly localTime: string;
  readonly lines: readonly string[];
}

export interface ConsentItem {
  readonly purpose: string;
  readonly title: string;
  readonly granted: boolean;
  readonly text: string;
}

export interface Consents {
  readonly items: readonly ConsentItem[];
  readonly notes: readonly string[];
}
