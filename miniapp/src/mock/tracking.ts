// The mock's water, mood, calendar, settings and consent records, for development and the component
// tests. Sentences follow the server's (api/src/dosegram/api), but the server stays the authority.
import { ApiError } from '../api/client';
import type {
  AccountSettings,
  AccountSettingsInput,
  BlobAsset,
  CalendarDay,
  Consents,
  Domain,
  TrackedDomain,
  InsightCalendar,
  LocalDate,
  MoodCheckin,
  MoodCheckinInput,
  MoodDay,
  MoodScale,
  MoodSummary,
  NotificationSettings,
  NotificationSettingsInput,
  TimeZonePreview,
  WaterDay,
  WaterEntry,
  WaterLogged,
  WaterSettings,
  WaterSettingsInput,
  WaterSummary,
} from '../api/types';
import blobAsset from './blob-states.json';
import { HYDRATION_GOAL_ML, HYDRATION_PRESETS_ML, WATER_SEED } from './data';
import { addDays, formatDate, toTime } from './dates';
import { lastDays, moodLine, seeded, waterBars } from './scenes';
import { MockFood } from './food';
import { duration, MockSleep } from './sleep';

const VALENCE = ['Very unpleasant', 'Unpleasant', 'Neutral', 'Pleasant', 'Very pleasant'];
const ENERGY = ['Very low', 'Low', 'Medium', 'High', 'Very high'];
/** A stored valence step reaches the blob levels V1, V2, V4, V6 and V7 (VISUALS 4.3). */
const LEVEL_OF_STEP = [1, 2, 4, 6, 7];
const LIMITS = { entryMinMl: 1, entryMaxMl: 5000, goalMinMl: 100, goalMaxMl: 10000, presetsMax: 4 };
const STATEMENT = 'Dosegram records how you feel. It does not assess it.';
const ZONES = ['Europe/Kyiv', 'Europe/Lisbon', 'Europe/London', 'America/New_York', 'Asia/Tokyo'];
const OFFSETS: Readonly<Record<string, string>> = {
  'Europe/Kyiv': '+02:00',
  'Europe/Lisbon': '+00:00',
  'Europe/London': '+00:00',
  'America/New_York': '-05:00',
  'Asia/Tokyo': '+09:00',
};

const litres = (ml: number): string => {
  const value = Math.round(ml / 10) / 100;
  return Number.isInteger(value) ? value.toFixed(1) : String(value);
};

const dayLabel = (date: LocalDate): string => formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' });
const count = (n: number, noun: string): string => (n === 1 ? `1 ${noun}` : `${n} ${noun}s`);

export function blobStateOf(valence: number, energy: number | undefined): string {
  return `blob.v${LEVEL_OF_STEP[valence - 1] ?? 4}.e${energy ?? 3}`;
}

function moodLabel(valence: number, energy: number | undefined): string {
  const words = VALENCE[valence - 1] ?? '';
  return energy === undefined ? words : `${words}. Energy: ${(ENERGY[energy - 1] ?? '').toLowerCase()}.`;
}

interface StoredWater extends WaterEntry {
  readonly minute: number;
}

interface StoredMood extends MoodCheckin {
  readonly minute: number;
}

export class MockTracking {
  private readonly consented = new Set<TrackedDomain>(['medication', 'hydration', 'mood', 'sleep', 'nutrition']);
  readonly sleep: MockSleep;
  readonly food: MockFood;
  private water: StoredWater[] = [];
  private moods: StoredMood[] = [];
  private goals: { from: LocalDate; goalMl: number | undefined }[] = [];
  private presets: readonly number[] = HYDRATION_PRESETS_ML;
  private waterVersion = 1;
  private account: AccountSettings = {
    language: 'en',
    languages: [{ value: 'en', label: 'English' }],
    timeZone: 'Europe/Kyiv',
    imageTheme: 'auto',
    version: 1,
  };
  private notifications: NotificationSettings = {
    discreet: false,
    notes: ['A medication reminder that falls inside quiet hours follows the quiet-hours rule of its schedule.'],
    version: 1,
  };
  private nextId = 1;

  constructor(
    private readonly today: () => LocalDate,
    private readonly minute: () => number,
    withHistory: boolean,
  ) {
    this.sleep = new MockSleep(today, minute, () => this.gate('sleep'), withHistory);
    this.food = new MockFood(today, minute, () => this.gate('nutrition'), withHistory);
    if (withHistory) this.seed();
  }

  /** What the chat's /privacy would do; lets tests show the "turned off" state. */
  withdraw(domain: TrackedDomain): void {
    this.consented.delete(domain);
  }

  isOn(domain: TrackedDomain): boolean {
    return this.consented.has(domain);
  }

  private gate(domain: TrackedDomain): void {
    if (!this.consented.has(domain)) throw new ApiError('consent_required', 'MOCK-0403');
  }

  private seed(): void {
    const today = this.today();
    this.goals = [{ from: addDays(today, -120), goalMl: HYDRATION_GOAL_ML }];
    const random = seeded(23);
    for (const date of lastDays(addDays(today, -1), 75)) {
      if (random() < 0.15) continue;
      const total = 900 + Math.round(random() * 14) * 100;
      this.storeWater(date, 9 * 60, Math.round(total / 2));
      this.storeWater(date, 15 * 60, total - Math.round(total / 2));
      if (random() < 0.8) {
        const valence = 2 + Math.round(random() * 3);
        this.storeMood(date, 20 * 60, Math.min(5, valence), random() < 0.6 ? 2 + Math.round(random() * 2) : undefined);
      }
    }
    for (const entry of WATER_SEED) this.storeWater(today, entry.minute, entry.ml);
    this.storeMood(today, 7 * 60 + 40, 4, 3);
  }

  private storeWater(date: LocalDate, minute: number, amountMl: number): StoredWater {
    const entry = { id: `water-${this.nextId++}`, date, time: toTime(minute), minute, amountMl, text: `${amountMl} ml` };
    this.water.push(entry);
    return entry;
  }

  private storeMood(date: LocalDate, minute: number, valence: number, energy: number | undefined): StoredMood {
    const checkin: StoredMood = {
      id: `mood-${this.nextId++}`,
      date,
      time: toTime(minute),
      minute,
      valence,
      energy,
      label: moodLabel(valence, energy),
      blobState: blobStateOf(valence, energy),
    };
    this.moods.push(checkin);
    return checkin;
  }

  // ---------- water ----------

  private goalOn(date: LocalDate): number | undefined {
    return this.goals.filter((goal) => goal.from <= date).at(-1)?.goalMl;
  }

  private waterOn(date: LocalDate): readonly StoredWater[] {
    return this.water.filter((entry) => entry.date === date).sort((a, b) => a.minute - b.minute);
  }

  waterDay(date: LocalDate): WaterDay {
    const entries = this.waterOn(date);
    const totalMl = entries.reduce((sum, entry) => sum + entry.amountMl, 0);
    const goalMl = this.goalOn(date);
    const percent = goalMl === undefined ? undefined : Math.floor((100 * totalMl) / goalMl);
    const figures =
      goalMl === undefined ? `${litres(totalMl)} l` : `${litres(totalMl)} of ${litres(goalMl)} l (${percent}%)`;
    return {
      date,
      totalMl,
      entries: entries.length,
      text: entries.length === 0 ? 'Nothing logged yet' : figures,
      goalMl,
      percent,
      vesselLabel:
        goalMl === undefined ? undefined : `Water: ${litres(totalMl)} of ${litres(goalMl)} litres, ${percent} percent`,
    };
  }

  waterSummary(): WaterSummary {
    this.gate('hydration');
    const today = this.today();
    const dates = lastDays(today, 7);
    const days = dates.map((date) => ({ date, totalMl: this.waterOn(date).reduce((sum, entry) => sum + entry.amountMl, 0) }));
    const any = days.some((day) => day.totalMl > 0);
    return {
      today: this.waterDay(today),
      presetsMl: this.presets,
      chart: any ? waterBars(days, this.goalOn(today)) : undefined,
      noDataText: any ? undefined : 'No data in this period',
    };
  }

  waterEntries(from: LocalDate, to: LocalDate): readonly WaterEntry[] {
    this.gate('hydration');
    return this.water.filter((entry) => entry.date >= from && entry.date <= to).sort((a, b) => a.minute - b.minute);
  }

  addWater(amountMl: number): WaterLogged {
    this.gate('hydration');
    if (amountMl < LIMITS.entryMinMl || amountMl > LIMITS.entryMaxMl) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'amountMl', code: 'out_of_range' }]);
    }
    const today = this.today();
    const before = this.waterDay(today);
    const entry = this.storeWater(today, this.minute(), amountMl);
    const day = this.waterDay(today);
    const goal = day.goalMl ?? Number.POSITIVE_INFINITY;
    return { entry, day, goalReachedNow: before.totalMl < goal && day.totalMl >= goal };
  }

  removeWater(id: string): WaterDay {
    this.gate('hydration');
    const entry = this.water.find((candidate) => candidate.id === id);
    if (!entry) throw new ApiError('not_found', 'MOCK-0404');
    this.water = this.water.filter((candidate) => candidate.id !== id);
    return this.waterDay(entry.date);
  }

  waterSettings(): WaterSettings {
    this.gate('hydration');
    return { presetsMl: this.presets, goalMl: this.goalOn(this.today()), limits: LIMITS, version: this.waterVersion };
  }

  saveWaterSettings(input: WaterSettingsInput, version: number): WaterSettings {
    this.gate('hydration');
    if (version !== this.waterVersion) throw new ApiError('version_conflict', 'MOCK-0412');
    const errors = [
      ...(input.goalMl !== undefined && (input.goalMl < LIMITS.goalMinMl || input.goalMl > LIMITS.goalMaxMl)
        ? [{ field: 'goalMl', code: 'out_of_range' }]
        : []),
      ...(input.presetsMl.some((amount) => amount < LIMITS.entryMinMl || amount > LIMITS.entryMaxMl)
        ? [{ field: 'presetsMl', code: 'out_of_range' }]
        : []),
    ];
    if (errors.length > 0) throw new ApiError('validation', 'MOCK-0422', errors);
    this.goals.push({ from: this.today(), goalMl: input.goalMl });
    this.presets = [...input.presetsMl];
    this.waterVersion += 1;
    return this.waterSettings();
  }

  // ---------- mood ----------

  moodScale(): MoodScale {
    this.gate('mood');
    return {
      valence: VALENCE.map((label, index) => ({ step: index + 1, label })),
      energy: ENERGY.map((label, index) => ({ step: index + 1, label })),
      blobRevision: blobAsset.revision,
      statement: STATEMENT,
    };
  }

  blobStates(): BlobAsset {
    this.gate('mood');
    return blobAsset as BlobAsset;
  }

  private moodsOn(date: LocalDate): readonly StoredMood[] {
    return this.moods.filter((checkin) => checkin.date === date).sort((a, b) => a.minute - b.minute);
  }

  moodDay(date: LocalDate): MoodDay {
    const checkins = this.moodsOn(date);
    const latest = checkins.at(-1);
    return {
      date,
      checkIns: checkins.length,
      text: latest?.label ?? 'No check-in yet',
      blobState: latest?.blobState ?? 'blob.none',
      latest,
    };
  }

  moodSummary(): MoodSummary {
    this.gate('mood');
    const today = this.today();
    return {
      today: this.moodDay(today),
      days: lastDays(today, 7).map((date) => {
        const checkins = this.moodsOn(date);
        const mean = checkins.reduce((sum, checkin) => sum + checkin.valence, 0) / Math.max(1, checkins.length);
        const step = Math.round(mean);
        return {
          date,
          label: formatDate(date, { weekday: 'short' }),
          name:
            checkins.length === 0
              ? `${dayLabel(date)}: no check-in`
              : `${dayLabel(date)}: ${mean.toFixed(1)} (${(VALENCE[step - 1] ?? '').toLowerCase()}), ${count(checkins.length, 'check-in')}`,
          blobState: checkins.length === 0 ? 'blob.none' : blobStateOf(step, undefined),
          checkIns: checkins.length,
        };
      }),
    };
  }

  moodCheckins(from: LocalDate, to: LocalDate): readonly MoodCheckin[] {
    this.gate('mood');
    return this.moods.filter((checkin) => checkin.date >= from && checkin.date <= to).sort((a, b) => a.minute - b.minute);
  }

  checkIn(input: MoodCheckinInput): { checkin: MoodCheckin; day: MoodDay } {
    this.gate('mood');
    if (input.valence < 1 || input.valence > 5) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'valence', code: 'out_of_range' }]);
    }
    const checkin = this.storeMood(this.today(), this.minute(), input.valence, input.energy);
    return { checkin, day: this.moodDay(checkin.date) };
  }

  removeCheckin(id: string): MoodDay {
    this.gate('mood');
    const checkin = this.moods.find((candidate) => candidate.id === id);
    if (!checkin) throw new ApiError('not_found', 'MOCK-0404');
    this.moods = this.moods.filter((candidate) => candidate.id !== id);
    return this.moodDay(checkin.date);
  }

  /** The mood chart of the insights overview. */
  moodChart(dates: readonly LocalDate[]) {
    const days = dates.map((date) => {
      const checkins = this.moodsOn(date);
      const mean = checkins.length === 0 ? null : checkins.reduce((sum, c) => sum + c.valence, 0) / checkins.length;
      return { date, mean, checkIns: checkins.length };
    });
    return days.some((day) => day.mean !== null) ? moodLine(days) : undefined;
  }

  // ---------- calendar ----------

  calendar(domain: Domain, month: string | undefined, medicationDay: (date: LocalDate) => [number, number]): InsightCalendar {
    this.gate(domain);
    const today = this.today();
    const shown = month ?? today.slice(0, 7);
    if (shown > today.slice(0, 7)) throw new ApiError('validation', 'MOCK-0422', [{ field: 'month', code: 'in_future' }]);
    const first = `${shown}-01`;
    const days: CalendarDay[] = [];
    for (let date = first; date.startsWith(shown); date = addDays(date, 1)) {
      days.push(this.cell(domain, date, today, medicationDay));
    }
    const previous = addDays(first, -1).slice(0, 7);
    const next = addDays(days.at(-1)!.date, 1).slice(0, 7);
    const valued = days.filter((day) => day.swatch.state === 'value');
    const monthName = formatDate(first, { month: 'long' });
    return {
      domain,
      month: shown,
      monthLabel: formatDate(first, { month: 'long', year: 'numeric' }),
      previousMonth: previous,
      nextMonth: next <= today.slice(0, 7) ? next : undefined,
      steps: STEPS[domain],
      weekStart: 1,
      days,
      legend: LEGENDS[domain],
      summary: `${monthName}${shown === today.slice(0, 7) ? ' so far' : ''}: ${valued.length === 0 ? 'nothing recorded' : `records on ${count(valued.length, 'day')}`}.`,
      domains: (['medication', 'hydration', 'mood', 'sleep'] as const).filter((candidate) => this.consented.has(candidate)),
    };
  }

  private cell(
    domain: Domain,
    date: LocalDate,
    today: LocalDate,
    medicationDay: (date: LocalDate) => [number, number],
  ): CalendarDay {
    const prefix = formatDate(date, { weekday: 'long', day: 'numeric', month: 'long' });
    const empty = (words: string): CalendarDay => ({ date, swatch: { state: 'empty', hasNoRecord: false }, name: `${prefix}, ${words}` });
    if (date > today) return { date, swatch: { state: 'future', hasNoRecord: false }, name: `${prefix}, still ahead` };
    if (domain === 'hydration') {
      const day = this.waterDay(date);
      if (day.entries === 0) return empty('no entry');
      const step = day.percent === undefined ? undefined : Math.max(1, Math.min(4, Math.floor((day.percent + 24) / 25)));
      return { date, swatch: { state: 'value', step, hasNoRecord: false }, name: `${prefix}, ${day.text}`, value: litres(day.totalMl) };
    }
    if (domain === 'sleep') {
      const minutes = this.sleep.minutesOn(date);
      if (minutes === undefined) return empty('no night logged');
      return {
        date,
        swatch: { state: 'value', step: 1, hasNoRecord: false },
        name: `${prefix}, ${duration(minutes)}, your check-ins`,
        value: `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}`,
      };
    }
    if (domain === 'mood') {
      const checkins = this.moodsOn(date);
      if (checkins.length === 0) return empty('no check-in');
      const mean = checkins.reduce((sum, checkin) => sum + checkin.valence, 0) / checkins.length;
      const step = Math.round(mean);
      return {
        date,
        swatch: { state: 'value', step, hasNoRecord: false, blobState: blobStateOf(step, undefined) },
        name: `${prefix}, ${mean.toFixed(1)} (${(VALENCE[step - 1] ?? '').toLowerCase()})`,
        value: String(step),
      };
    }
    const [taken, scheduled] = medicationDay(date);
    if (scheduled === 0) return empty('nothing scheduled');
    const step = taken === 0 ? 1 : taken < scheduled ? 2 : 3;
    return {
      date,
      swatch: { state: 'value', step, hasNoRecord: false },
      name: `${prefix}, ${taken} of ${scheduled} taken`,
      value: `${taken}/${scheduled}`,
    };
  }

  // ---------- settings and consent ----------

  settings(): AccountSettings {
    return this.account;
  }

  saveSettings(input: AccountSettingsInput, version: number): AccountSettings {
    if (version !== this.account.version) throw new ApiError('version_conflict', 'MOCK-0412');
    if (!ZONES.includes(input.timeZone)) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'timeZone', code: 'unknown' }]);
    }
    this.account = { ...this.account, ...input, version: version + 1 };
    return this.account;
  }

  notificationSettings(): NotificationSettings {
    return this.notifications;
  }

  saveNotificationSettings(input: NotificationSettingsInput, version: number): NotificationSettings {
    if (version !== this.notifications.version) throw new ApiError('version_conflict', 'MOCK-0412');
    if (input.quietHours && input.quietHours.start === input.quietHours.end) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'quietHours', code: 'quiet_hours_empty' }]);
    }
    this.notifications = { ...this.notifications, ...input, quietHours: input.quietHours, version: version + 1 };
    return this.notifications;
  }

  timeZones() {
    return ZONES.map((id) => ({ id, offset: OFFSETS[id] ?? '+00:00' }));
  }

  previewTimeZone(timeZone: string): TimeZonePreview {
    if (!ZONES.includes(timeZone)) throw new ApiError('validation', 'MOCK-0422', [{ field: 'timeZone', code: 'unknown' }]);
    const lines = this.consented.has('medication')
      ? ['3 schedules follow your time zone and keep their clock times.']
      : ['No schedule is changed by this.'];
    return { timeZone, localTime: `It is ${toTime(this.minute())} there now.`, lines };
  }

  consents(): Consents {
    const titles: readonly [string, string, string][] = [
      ['medication', 'Medication', '/meds'],
      ['hydration', 'Water', '/water'],
      ['mood', 'Mood', '/mood'],
      ['sleep', 'Sleep', '/sleep'],
      ['nutrition', 'Meals', '/food'],
    ];
    return {
      items: [
        ...titles.map(([purpose, title, command]) =>
          this.consented.has(purpose as TrackedDomain)
            ? { purpose, title, granted: true, text: 'On since 2 Nov 2026.' }
            : { purpose, title, granted: false, text: `Off. Send ${command} in the chat to turn it on.` },
        ),
        {
          purpose: 'images_in_chat',
          title: 'Report images in the chat',
          granted: false,
          text: 'Off. The chat asks for it when an image is first requested.',
        },
      ],
      notes: [
        'To withdraw a consent, send /privacy in the chat. The app records no consent of its own.',
        'Telegram can read the messages and images in your chat with Dosegram. Bot chats are not end-to-end encrypted.',
      ],
    };
  }
}

const STEPS: Readonly<Record<Domain, number>> = { medication: 3, hydration: 4, mood: 5, sleep: 1 };

const LEGENDS: Readonly<Record<Domain, InsightCalendar['legend']>> = {
  sleep: [
    { label: 'Night logged', swatch: { state: 'value', step: 1, hasNoRecord: false } },
    { label: 'No night logged', swatch: { state: 'empty', hasNoRecord: false } },
  ],
  medication: [
    { label: 'All taken', swatch: { state: 'value', step: 3, hasNoRecord: false } },
    { label: 'Some taken', swatch: { state: 'value', step: 2, hasNoRecord: false } },
    { label: 'None taken', swatch: { state: 'value', step: 1, hasNoRecord: false } },
    { label: 'Nothing scheduled', swatch: { state: 'empty', hasNoRecord: false } },
    { label: 'No record', swatch: { state: 'value', step: 2, hasNoRecord: true } },
  ],
  hydration: [
    { label: 'Goal reached', swatch: { state: 'value', step: 4, hasNoRecord: false } },
    { label: 'Up to three quarters', swatch: { state: 'value', step: 3, hasNoRecord: false } },
    { label: 'Up to half', swatch: { state: 'value', step: 2, hasNoRecord: false } },
    { label: 'Up to a quarter', swatch: { state: 'value', step: 1, hasNoRecord: false } },
    { label: 'No entry', swatch: { state: 'empty', hasNoRecord: false } },
  ],
  mood: [
    ...[5, 4, 3, 2, 1].map((step) => ({
      label: `${step} ${(VALENCE[step - 1] ?? '').toLowerCase()}`,
      swatch: { state: 'value' as const, step, hasNoRecord: false, blobState: blobStateOf(step, undefined) },
    })),
    { label: 'No check-in', swatch: { state: 'empty', hasNoRecord: false } },
  ],
};
