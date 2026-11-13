// The mock's sleep records, for development and the component tests. Sentences follow the server's
// (api/src/dosegram/api/Sleep.scala), but the server stays the authority.
import { ApiError } from '../api/client';
import type {
  Chart,
  LocalDate,
  SceneNode,
  SleepCheckinKind,
  SleepEpisode,
  SleepEpisodeInput,
  SleepSettings,
  SleepStatus,
  SleepSummary,
} from '../api/types';
import { addDays, formatDate, toTime } from './dates';
import { lastDays, seeded } from './scenes';

const LIMITS = { goalMinMinutes: 60, goalMaxMinutes: 1080 };
const SOURCE = 'From your check-ins';
/** The mock's zone is Europe/Kyiv; November has no clock change, so the offset is fixed. */
const OFFSET = '+02:00';
const WIDTH = 320;
const DAY_MINUTES = 24 * 60;

export const duration = (minutes: number): string =>
  minutes < 60 ? `${minutes} min` : `${Math.floor(minutes / 60)} h ${String(minutes % 60).padStart(2, '0')} min`;

const dayLabel = (date: LocalDate): string => formatDate(date, { weekday: 'short', day: 'numeric', month: 'short' });
const shortDate = (date: LocalDate): string => formatDate(date, { day: 'numeric', month: 'short' });
const instant = (date: LocalDate, minute: number): string => `${date}T${toTime(minute)}:00${OFFSET}`;

/** Minutes since midnight of `date` in the mock zone; negative on the day before. */
function minuteOn(date: LocalDate, at: string): number {
  const [day, time] = [at.slice(0, 10), at.slice(11, 16)];
  const minute = Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5));
  return day === date ? minute : day < date ? minute - DAY_MINUTES : minute + DAY_MINUTES;
}

interface Stored {
  readonly id: string;
  readonly kind: 'night' | 'nap';
  readonly date: LocalDate;
  /** Minutes from midnight of `date`; a bedtime the evening before is negative. */
  readonly onset: number;
  readonly wake: number;
}

export class MockSleep {
  private episodes: Stored[] = [];
  private goal: number | undefined;
  private version = 1;
  private open: number | undefined;
  private nextId = 1;

  constructor(
    private readonly today: () => LocalDate,
    private readonly minute: () => number,
    private readonly gate: () => void,
    withHistory: boolean,
  ) {
    if (withHistory) this.seed();
  }

  private seed(): void {
    this.goal = 450;
    const random = seeded(41);
    for (const date of lastDays(this.today(), 21)) {
      if (random() < 0.2) continue;
      const onset = -60 + Math.round(random() * 6) * 15;
      const wake = 6 * 60 + 30 + Math.round(random() * 4) * 15;
      this.store('night', date, onset, wake);
    }
  }

  private store(kind: 'night' | 'nap', date: LocalDate, onset: number, wake: number): Stored {
    const episode = { id: `sleep-${this.nextId++}`, kind, date, onset, wake };
    this.episodes.push(episode);
    return episode;
  }

  private view(stored: Stored): SleepEpisode {
    const minutes = stored.wake - stored.onset;
    const onsetDay = stored.onset < 0 ? addDays(stored.date, -1) : stored.date;
    const onsetMinute = stored.onset < 0 ? stored.onset + DAY_MINUTES : stored.onset;
    const bedtime = toTime(onsetMinute);
    const wakeTime = toTime(stored.wake);
    const noun = stored.kind === 'nap' ? 'Nap' : 'Night';
    return {
      id: stored.id,
      kind: stored.kind,
      date: stored.date,
      onsetAt: instant(onsetDay, onsetMinute),
      wakeAt: instant(stored.date, stored.wake),
      bedtime,
      wakeTime,
      minutes,
      duration: duration(minutes),
      source: SOURCE,
      text: `${noun} of ${dayLabel(stored.date)}: ${bedtime} to ${wakeTime}, ${duration(minutes)}`,
    };
  }

  private nightOn(date: LocalDate): Stored | undefined {
    return this.episodes.find((episode) => episode.kind === 'night' && episode.date === date);
  }

  /** The minutes of the night that ended on a day, for the calendar. */
  minutesOn(date: LocalDate): number | undefined {
    const night = this.nightOn(date);
    return night && night.wake - night.onset;
  }

  status(needsBedtime = false): SleepStatus {
    const latest = [...this.episodes]
      .filter((episode) => episode.kind === 'night')
      .sort((a, b) => (a.date === b.date ? a.wake - b.wake : a.date < b.date ? -1 : 1))
      .at(-1);
    const lastNight = latest && this.view(latest);
    const openSince = this.open === undefined ? undefined : toTime((this.open + DAY_MINUTES) % DAY_MINUTES);
    const text = openSince
      ? `Going to sleep recorded at ${openSince}.`
      : lastNight
        ? `Last: ${lastNight.bedtime} to ${lastNight.wakeTime}, ${lastNight.duration}.`
        : 'No night logged yet';
    return {
      text,
      openSince,
      lastNight,
      goalMinutes: this.goal,
      goalText: this.goal === undefined ? undefined : `Your goal: ${duration(this.goal)}`,
      needsBedtime,
    };
  }

  summary(): SleepSummary {
    this.gate();
    const dates = lastDays(this.today(), 7);
    const nights = dates.map((date) => ({ date, minutes: this.minutesOn(date) }));
    const logged = nights.filter((night) => night.minutes !== undefined);
    const average =
      logged.length >= 3
        ? `average ${duration(Math.round(logged.reduce((sum, night) => sum + night.minutes!, 0) / logged.length))}`
        : `${3 - logged.length} more ${3 - logged.length === 1 ? 'night' : 'nights'} for an average`;
    return {
      status: this.status(),
      lines:
        logged.length === 0
          ? []
          : [`${logged.length} ${logged.length === 1 ? 'night' : 'nights'} logged`, average, 'your check-ins', `${logged.length} of 7 days have data`],
      days: nights.map((night) => ({
        date: night.date,
        label: formatDate(night.date, { weekday: 'short' }),
        name: `${dayLabel(night.date)}: ${night.minutes === undefined ? 'no night logged' : duration(night.minutes)}`,
        minutes: night.minutes,
      })),
      chart: logged.length === 0 ? undefined : this.chart(nights),
      noDataText: logged.length === 0 ? 'No data in this period' : undefined,
    };
  }

  episodesIn(from: LocalDate, to: LocalDate): readonly SleepEpisode[] {
    this.gate();
    return this.episodes
      .filter((episode) => episode.date >= from && episode.date <= to)
      .sort((a, b) => (a.date === b.date ? a.wake - b.wake : a.date < b.date ? -1 : 1))
      .map((episode) => this.view(episode));
  }

  add(input: SleepEpisodeInput): SleepEpisode {
    this.gate();
    const date = input.wakeAt.slice(0, 10);
    const onset = minuteOn(date, input.onsetAt);
    const wake = minuteOn(date, input.wakeAt);
    const refuse = (field: string, code: string) => new ApiError('validation', 'MOCK-0422', [{ field, code }]);
    if (wake <= onset) throw refuse('wakeAt', 'wake_before_sleep');
    if (wake - onset > (input.kind === 'nap' ? 6 : 18) * 60) throw refuse('wakeAt', 'too_long');
    if (date > this.today() || (date === this.today() && wake > this.minute())) throw refuse('wakeAt', 'in_future');
    if (input.kind === 'night' && this.nightOn(date)) throw refuse('wakeAt', 'night_exists');
    const overlaps = this.episodes.some((other) => other.date === date && other.onset < wake && onset < other.wake);
    if (overlaps) throw refuse('onsetAt', 'overlap');
    return this.view(this.store(input.kind, date, onset, wake));
  }

  remove(id: string): SleepStatus {
    this.gate();
    if (!this.episodes.some((episode) => episode.id === id)) throw new ApiError('not_found', 'MOCK-0404');
    this.episodes = this.episodes.filter((episode) => episode.id !== id);
    return this.status();
  }

  checkIn(kind: SleepCheckinKind): SleepStatus {
    this.gate();
    const now = this.minute();
    if (kind === 'going_to_sleep') {
      this.open = now;
      return this.status();
    }
    if (this.open === undefined) return this.status(true);
    const onset = this.open > now ? this.open - DAY_MINUTES : this.open;
    this.open = undefined;
    this.store('night', this.today(), onset, now);
    return this.status();
  }

  settings(): SleepSettings {
    this.gate();
    return { goalMinutes: this.goal, limits: LIMITS, version: this.version };
  }

  saveSettings(goalMinutes: number | undefined, version: number): SleepSettings {
    this.gate();
    if (version !== this.version) throw new ApiError('version_conflict', 'MOCK-0412');
    if (goalMinutes !== undefined && (goalMinutes < LIMITS.goalMinMinutes || goalMinutes > LIMITS.goalMaxMinutes)) {
      throw new ApiError('validation', 'MOCK-0422', [{ field: 'goalMinutes', code: 'out_of_range' }]);
    }
    this.goal = goalMinutes;
    this.version += 1;
    return this.settings();
  }

  /** Bars of duration per night from zero, with the own goal dashed; a day without a night is a gap. */
  chart(nights: readonly { readonly date: LocalDate; readonly minutes?: number }[]): Chart {
    const plotHeight = 96;
    const axisWidth = 44;
    const top = Math.max(this.goal ?? 0, ...nights.map((night) => night.minutes ?? 0)) * 1.1;
    const pitch = (WIDTH - axisWidth) / nights.length;
    const scale = (minutes: number): number => plotHeight - (minutes / top) * plotHeight;
    const bars = nights.flatMap((night, index): SceneNode[] =>
      night.minutes === undefined
        ? []
        : [
            {
              kind: 'rect',
              x: axisWidth + index * pitch + pitch * 0.15,
              y: scale(night.minutes),
              width: pitch * 0.7,
              height: plotHeight - scale(night.minutes),
              radius: Math.min(2, pitch * 0.2),
              fill: 'domain-sleep',
              mark: index,
            },
          ],
    );
    const goal: SceneNode[] =
      this.goal === undefined
        ? []
        : [
            { kind: 'line', x1: axisWidth, y1: scale(this.goal), x2: WIDTH, y2: scale(this.goal), stroke: 'text-muted', dashed: true },
            { kind: 'text', x: 0, y: scale(this.goal) + 4, text: `${Math.floor(this.goal / 60)} h ${String(this.goal % 60).padStart(2, '0')}`, fill: 'text-muted' },
          ];
    const hits = nights.map(
      (_, index): SceneNode => ({ kind: 'hit', x: axisWidth + index * pitch, y: 0, width: pitch, height: plotHeight, mark: index }),
    );
    const valueOf = (minutes: number | undefined): string => (minutes === undefined ? 'no night logged' : duration(minutes));
    const logged = nights.filter((night) => night.minutes !== undefined).length;
    return {
      id: 'sleep-by-day',
      title: 'Sleep per night',
      summary: `Sleep per night, ${shortDate(nights[0]!.date)} to ${shortDate(nights.at(-1)!.date)}, from your check-ins. Nights logged on ${logged} of ${nights.length} days.${this.goal === undefined ? '' : ` Your goal: ${duration(this.goal)}.`}`,
      scene: {
        width: WIDTH,
        height: plotHeight + 24,
        nodes: [
          ...bars,
          { kind: 'line', x1: axisWidth, y1: plotHeight, x2: WIDTH, y2: plotHeight, stroke: 'separator' },
          ...goal,
          ...hits,
        ],
      },
      table: {
        caption: 'Sleep per night',
        columns: ['Day', 'Sleep'],
        rows: nights.map((night) => [shortDate(night.date), night.minutes === undefined ? 'No night logged' : duration(night.minutes)]),
      },
      marks: nights.map((night) => `${shortDate(night.date)}: ${valueOf(night.minutes)}`),
    };
  }
}
