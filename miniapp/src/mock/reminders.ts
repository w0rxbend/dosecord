// The reminders of the in-memory backend (ADR-023): the hub, the presets, the day profile, the pause and
// tomorrow's timeline. A small fixed catalogue; the server computes the real times.
import { ApiError } from '../api/client';
import type {
  DayProfile,
  DayProfileInput,
  PromptPreset,
  ReminderKind,
  ReminderPresets,
  Reminders,
  ReminderTimeline,
} from '../api/types';

const KINDS: readonly (readonly [string, string, boolean])[] = [
  ['water', 'Water', true],
  ['mood', 'Mood', true],
  ['sleep_bedtime', 'Bedtime', true],
  ['sleep_morning', 'Morning', true],
  ['meal', 'Meals', true],
];

const PRESETS: readonly PromptPreset[] = [
  { id: 'water.work_hours_30', kind: 'water', name: 'Work hours, every 30 min', available: true, preview: ['2026-11-17T07:30:00Z', '2026-11-17T08:00:00Z'] },
  { id: 'water.hourly', kind: 'water', name: 'Every hour, 08:00 to 21:00', available: true, preview: ['2026-11-17T07:00:00Z'] },
  { id: 'mood.evening', kind: 'mood', name: 'Once in the evening', available: true, preview: ['2026-11-17T18:00:00Z'] },
  { id: 'meal.anchors', kind: 'meal', name: 'At meal times, if not logged', available: true, preview: ['2026-11-17T06:00:00Z', '2026-11-17T11:00:00Z'] },
];

export class MockReminders {
  /** `daySaved` false is an account whose day profile is still the product's default. */
  constructor(daySaved = true) {
    this.day = { ...this.day, saved: daySaved };
  }

  private plans = new Map<string, string>();
  private pausedUntil: string | undefined;
  private day: DayProfile = {
    version: 1,
    wake: '07:00',
    breakfast: '08:00',
    lunch: '13:00',
    dinner: '19:00',
    bedtime: '23:00',
    workDays: [1, 2, 3, 4, 5],
    workStart: '09:00',
    workEnd: '18:00',
    quietStart: '22:00',
    quietEnd: '07:00',
  };

  view(): Reminders {
    const kinds: ReminderKind[] = KINDS.map(([kind, name, available]) => {
      const presetId = this.plans.get(kind);
      const preset = PRESETS.find((candidate) => candidate.id === presetId);
      return preset ? { kind, name, available, presetId, presetName: preset.name, nextAt: preset.preview[0] } : { kind, name, available };
    });
    return { timeZone: 'Europe/Kyiv', kinds, medicationSchedules: 2, day: this.day, pausedUntil: this.pausedUntil };
  }

  presets(): ReminderPresets {
    return { prompts: PRESETS };
  }

  turnOn(presetId: string): Reminders {
    const preset = PRESETS.find((candidate) => candidate.id === presetId && candidate.available);
    if (!preset) throw new ApiError('validation', 'MOCK-0422', [{ field: 'presetId', code: 'unavailable' }]);
    this.plans.set(preset.kind, preset.id);
    return this.view();
  }

  turnOff(kind: string): Reminders {
    this.plans.delete(kind);
    return this.view();
  }

  pause(hours: number): Reminders {
    this.pausedUntil = hours > 0 ? '2026-11-16T12:00:00Z' : undefined;
    return this.view();
  }

  saveDay(input: DayProfileInput, version: number): DayProfile {
    if (version !== this.day.version) throw new ApiError('version_conflict', 'MOCK-0412');
    if (input.workStart >= input.workEnd) throw new ApiError('validation', 'MOCK-0422', [{ field: 'workStart', code: 'order' }]);
    this.day = { ...input, version: this.day.version + 1, saved: true };
    return this.day;
  }

  timeline(): ReminderTimeline {
    const entries = [...this.plans.values()].flatMap((id) => {
      const preset = PRESETS.find((candidate) => candidate.id === id);
      return preset ? preset.preview.map((at) => ({ at, kind: preset.kind, name: preset.name })) : [];
    });
    return { date: '2026-11-17', entries: [...entries, { at: '2026-11-17T06:00:00Z', kind: 'medication', name: 'Medication' }] };
  }
}
