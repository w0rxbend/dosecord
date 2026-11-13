import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from './client';
import { createHttpApi, type HttpApiConfig } from './http';
import { errorText } from '../i18n/en';
import type { MedicationDraft, ScheduleDraft } from './types';

// 13 November 2026, 12:00:00 UTC: the launch data below was signed 10 seconds earlier.
const NOW = 1794571210_000;
const INIT_DATA = 'auth_date=1794571200&user=%7B%22id%22%3A424242%7D&hash=canary-hash';

interface Call {
  readonly method: string;
  readonly url: string;
  readonly headers: Record<string, string>;
  readonly body: unknown;
}

type Answer = { status: number; body?: unknown } | 'offline';

/** Replaces fetch with a script of answers per "METHOD path" and records every request. */
function serve(routes: Record<string, Answer[]>): Call[] {
  const calls: Call[] = [];
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    const method = init.method ?? 'GET';
    calls.push({
      method,
      url,
      headers: init.headers as Record<string, string>,
      body: typeof init.body === 'string' ? JSON.parse(init.body) : undefined,
    });
    const queue = routes[`${method} ${url}`];
    const answer = queue?.shift();
    if (answer === undefined) return Promise.reject(new Error(`unexpected request ${method} ${url}`));
    if (answer === 'offline') return Promise.reject(new TypeError('offline'));
    const status = answer.status;
    return Promise.resolve(new Response(status === 204 ? null : JSON.stringify(answer.body ?? {}), { status }));
  });
  return calls;
}

const session = (token: string): Answer => ({
  status: 201,
  body: {
    token,
    expiresAt: '2026-11-13T12:15:00Z',
    idleTimeoutSeconds: 900,
    bootstrap: { timeZone: 'UTC', limits: { medicationNameMax: 80, instructionsMax: 500, doseUnitMax: 16, scheduleTimesMax: 12 } },
  },
});

const problem = (status: number, code: string, errors: unknown[] = []): Answer => ({
  status,
  body: { type: `urn:dosegram:problem:${code}`, title: 'A title that is never shown', status, code, requestId: 'req-1', errors },
});

const day = {
  date: '2026-11-13',
  dateLabel: 'Fri 13 Nov',
  timeZone: 'UTC',
  isToday: true,
  previousDate: '2026-11-12',
  nextDate: '2026-11-14',
  nowMinute: 492,
  occurrences: [
    { id: 'o1', medicationId: 'm1', medicationName: 'Medication A', scheduledTime: '08:00', scheduledAt: '2026-11-13T08:00:00Z', state: 'due', doseText: '1 tablet' },
    { id: 'o2', medicationId: 'm1', medicationName: 'Medication A', scheduledTime: '21:30', scheduledAt: '2026-11-13T21:30:00Z', state: 'pending' },
  ],
};

const medication = {
  id: 'm1',
  name: 'Medication A',
  status: 'active',
  version: 1794571200000000,
  createdAt: '2026-11-13T08:00:00Z',
  schedules: [],
  doseAmount: '1',
  doseUnit: 'tablet',
  instructions: 'with water',
};

const draft: MedicationDraft = {
  name: 'Medication A',
  form: 'tablet',
  strengthValue: '500',
  strengthUnit: 'mg',
  doseAmount: '1',
  doseUnit: 'tablet',
  colourTag: 'white',
  shapeTag: 'round',
  notes: 'with water',
  showNameInReminders: true,
};

function config(overrides: Partial<HttpApiConfig> = {}): HttpApiConfig {
  return { baseUrl: '/api/v1', initData: INIT_DATA, colorScheme: 'dark', platform: 'android', now: () => NOW, ...overrides };
}

afterEach(() => vi.unstubAllGlobals());

describe('HTTP API client', () => {
  it('sends the launch data to the session route only, in the body, and then the bearer token', async () => {
    const calls = serve({
      'POST /api/v1/session': [session('token-1')],
      'GET /api/v1/today': [{ status: 200, body: day }],
    });
    const api = createHttpApi(config());

    const bootstrap = await api.open();
    await api.client.today();

    expect(bootstrap.timeZone).toBe('UTC');
    expect(calls[0]?.body).toMatchObject({ initData: INIT_DATA, colorScheme: 'dark', platform: 'android' });
    expect(calls[0]?.headers).not.toHaveProperty('Authorization');
    expect(calls[1]?.headers.Authorization).toBe('Bearer token-1');
    // MA-LAUNCH-4: no URL, no header and no later body holds the launch data.
    const elsewhere = JSON.stringify([calls.map((call) => [call.url, call.headers]), calls.slice(1).map((call) => call.body)]);
    expect(elsewhere).not.toContain('canary-hash');
  });

  it('keeps the token and the launch data out of every client-side store', async () => {
    serve({ 'POST /api/v1/session': [session('token-canary')], 'GET /api/v1/today': [{ status: 200, body: day }] });
    const api = createHttpApi(config());
    await api.open();
    await api.client.today();

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(document.cookie).toBe('');
  });

  it.each([
    [problem(403, 'account_required'), 'account_required'],
    [problem(401, 'unauthenticated'), 'unauthorised'],
    [problem(401, 'client_too_old'), 'client_too_old'],
    [problem(500, 'internal'), 'server'],
    ['offline' as const, 'network'],
  ])('maps a refused session exchange to the code the gate screens use', async (answer, code) => {
    serve({ 'POST /api/v1/session': [answer] });

    const failure = await createHttpApi(config()).open().catch((cause: unknown) => cause);

    expect(failure).toBeInstanceOf(ApiError);
    expect((failure as ApiError).code).toBe(code);
  });

  it('maps a day to the Today view: read-only, answered in the chat, with the day line', async () => {
    serve({ 'POST /api/v1/session': [session('t')], 'GET /api/v1/today': [{ status: 200, body: day }] });
    const api = createHttpApi(config());
    await api.open();

    const view = await api.client.today();

    expect(view).toMatchObject({ date: '2026-11-13', dateLabel: 'Fri 13 Nov', isToday: true, readOnly: true, answerInChat: true, nowMinute: 492 });
    expect(view.occurrences[0]).toMatchObject({ id: 'o1', doseText: '1 tablet', state: 'due', dueForMinutes: 12, snoozeChoicesMinutes: [] });
    expect(view.occurrences[1]).toMatchObject({ doseText: '', dueForMinutes: undefined });
    expect(view.dayLine).toEqual([
      { minute: 480, kind: 'dose', state: 'due' },
      { minute: 1290, kind: 'dose', state: 'pending' },
    ]);
  });

  it('maps 403 consent_required to its own code, so the screen says where consent is given', async () => {
    serve({ 'POST /api/v1/session': [session('t')], 'GET /api/v1/today': [problem(403, 'consent_required')] });
    const api = createHttpApi(config());
    await api.open();

    const failure = await api.client.today().catch((cause: unknown) => cause);

    expect((failure as ApiError).code).toBe('consent_required');
    expect(errorText('consent_required')).toContain('only with your consent');
  });

  it('asks for another day through days/{date}', async () => {
    const calls = serve({ 'POST /api/v1/session': [session('t')], 'GET /api/v1/days/2026-11-14': [{ status: 200, body: day }] });
    const api = createHttpApi(config());
    await api.open();

    await api.client.today('2026-11-14');

    expect(calls[1]?.url).toBe('/api/v1/days/2026-11-14');
  });

  it('creates a medication with an idempotency key and only the members the server keeps', async () => {
    const calls = serve({ 'POST /api/v1/session': [session('t')], 'POST /api/v1/medications': [{ status: 201, body: medication }] });
    const api = createHttpApi(config());
    await api.open();

    const created = await api.client.createMedication(draft);

    expect(calls[1]?.body).toEqual({ name: 'Medication A', doseAmount: '1', doseUnit: 'tablet', instructions: 'with water' });
    expect(calls[1]?.headers['Idempotency-Key']).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(created).toMatchObject({ id: 'm1', version: 1794571200000000, doseAmount: '1', notes: 'with water', status: 'active' });
  });

  it('edits a medication on the version it read', async () => {
    const calls = serve({ 'POST /api/v1/session': [session('t')], 'PATCH /api/v1/medications/m1': [{ status: 200, body: medication }] });
    const api = createHttpApi(config());
    await api.open();

    await api.client.updateMedication('m1', draft, 1794571200000000);

    expect(calls[1]?.headers['If-Match']).toBe('"v1794571200000000"');
  });

  it('offers only the fields the server keeps', async () => {
    serve({
      'POST /api/v1/session': [session('t')],
      'GET /api/v1/medication-options': [
        { status: 200, body: { doseUnits: ['mg', 'tablet'], limits: { medicationNameMax: 80, instructionsMax: 500, doseUnitMax: 16, scheduleTimesMax: 12 } } },
      ],
      'GET /api/v1/schedule-options': [
        { status: 200, body: { patterns: ['fixed_times', 'as_needed'], timesMax: 12, previewDays: 7, timeZone: 'UTC', today: '2026-11-13' } },
      ],
    });
    const api = createHttpApi(config());
    await api.open();

    const medications = await api.client.medicationOptions();
    const schedules = await api.client.scheduleOptions();

    expect(medications).toMatchObject({ forms: [], strengthUnits: [], colourTags: [], shapeTags: [], reminderNameToggle: false });
    expect(medications.doseUnits).toEqual([{ value: 'mg', label: 'mg' }, { value: 'tablet', label: 'tablet' }]);
    expect(medications.limits).toEqual({ nameMax: 80, notesMax: 500 });
    expect(schedules.patterns.map((pattern) => pattern.value)).toEqual(['fixed_times', 'weekly', 'as_needed']);
    expect(schedules).toMatchObject({ maxRows: 12, today: '2026-11-13', perTimeDose: false });
    expect(schedules.weekdays).toHaveLength(7);
  });

  it('sends a weekly draft as fixed times on weekdays and reads the preview', async () => {
    const weekly: ScheduleDraft = {
      pattern: 'weekly',
      rows: [{ time: '08:00', dose: '1' }],
      weekdays: [1, 3],
      startDate: '2026-11-13',
      endDate: null,
    };
    const preview = {
      summary: 'on Mon, Wed 08:00',
      timeZone: 'Europe/Kyiv',
      days: [{ date: '2026-11-16', label: 'Mon 16 Nov', occurrences: [{ time: '08:00', at: '2026-11-16T06:00:00Z', note: 'Clock change' }] }],
    };
    const calls = serve({ 'POST /api/v1/session': [session('t')], 'POST /api/v1/schedule-previews': [{ status: 200, body: preview }] });
    const api = createHttpApi(config());
    await api.open();

    const shown = await api.client.previewSchedule('m1', weekly);

    expect(calls[1]?.body).toEqual({
      draft: { pattern: 'fixed_times', times: ['08:00'], weekdays: [1, 3], startDate: '2026-11-13' },
    });
    expect(shown).toEqual({
      summary: 'on Mon, Wed 08:00',
      zoneLabel: 'Europe/Kyiv',
      days: [{ date: '2026-11-16', label: 'Mon 16 Nov', occurrences: [{ time: '08:00', doseText: '', note: 'Clock change' }] }],
      moreCount: 0,
    });
  });

  it('maps server validation entries to the fields and codes of the forms', async () => {
    serve({
      'POST /api/v1/session': [session('t')],
      'POST /api/v1/medications': [
        problem(422, 'validation_failed', [
          { field: 'name', code: 'duplicate' },
          { field: 'instructions', code: 'too_long' },
          { field: 'doseUnit', code: 'too_long' },
        ]),
      ],
    });
    const api = createHttpApi(config());
    await api.open();

    const failure = (await api.client.createMedication(draft).catch((cause: unknown) => cause)) as ApiError;

    expect(failure.code).toBe('validation');
    expect(failure.reference).toBe('req-1');
    expect(failure.fields).toEqual([
      { field: 'name', code: 'name_taken' },
      { field: 'notes', code: 'notes_too_long' },
      { field: 'doseUnit', code: 'too_long' },
    ]);
  });

  it('answers a 401 inside the freshness window with one silent exchange and repeats the request', async () => {
    const ended = vi.fn();
    const calls = serve({
      'POST /api/v1/session': [session('first'), session('second')],
      'GET /api/v1/today': [problem(401, 'session_expired'), { status: 200, body: day }],
    });
    const api = createHttpApi(config({ onSessionEnded: ended }));
    await api.open();

    const view = await api.client.today();

    expect(view.date).toBe('2026-11-13');
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'POST /api/v1/session',
      'GET /api/v1/today',
      'POST /api/v1/session',
      'GET /api/v1/today',
    ]);
    expect(calls[3]?.headers.Authorization).toBe('Bearer second');
    expect(ended).not.toHaveBeenCalled();
  });

  it('reports the end of the session when the launch data is older than 300 seconds', async () => {
    const ended = vi.fn();
    const calls = serve({ 'POST /api/v1/session': [session('first')], 'GET /api/v1/today': [problem(401, 'session_expired')] });
    let now = NOW;
    const api = createHttpApi(config({ onSessionEnded: ended, now: () => now }));
    await api.open();
    now = NOW + 300_000;

    const failure = (await api.client.today().catch((cause: unknown) => cause)) as ApiError;

    expect(failure.code).toBe('unauthorised');
    expect(ended).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(2);
  });

  it('refuses the resources the server does not have yet without a request', async () => {
    const calls = serve({ 'POST /api/v1/session': [session('t')] });
    const api = createHttpApi(config());
    await api.open();

    const failures = await Promise.all([
      api.client.addHydration(250, 'key').catch((cause: unknown) => cause),
      api.client.actOnOccurrence('o1', { kind: 'taken' }, 'key').catch((cause: unknown) => cause),
    ]);

    expect(failures.map((failure) => (failure as ApiError).code)).toEqual(['unavailable', 'unavailable']);
    expect(calls).toHaveLength(1);
  });

  it('reads the insights of a period: the blocks as the server worded them, the vessel and the periods on offer', async () => {
    const overview = {
      rangeDays: 7,
      rangeLabel: '7 Nov to 13 Nov',
      timeZone: 'UTC',
      ranges: [7, 30],
      blocks: [
        { id: 'medication', title: 'Medication', lines: [], noDataText: 'No data in this period' },
        {
          id: 'hydration',
          title: 'Water',
          lines: ['2 more recorded days for an average', '1 of 7 days have data'],
          vessel: { valueMl: 1250, goalMl: 2000, label: 'Water today: 1.25 of 2.0 l (62%)' },
        },
        { id: 'mood', title: 'Mood', lines: ['1 check-in', '1 of 7 days have data'] },
      ],
    };
    const calls = serve({
      'POST /api/v1/session': [session('t')],
      'GET /api/v1/insights/overview?range=7': [{ status: 200, body: overview }],
    });
    const api = createHttpApi(config());
    await api.open();

    const read = await api.client.insightsOverview(7);

    expect(calls[1]?.headers.Authorization).toBe('Bearer t');
    expect(read.rangeLabel).toBe('7 Nov to 13 Nov');
    expect(read.ranges).toEqual([7, 30]);
    expect(read.blocks.map((block) => block.id)).toEqual(['medication', 'hydration', 'mood']);
    expect(read.blocks[0]).toEqual({ id: 'medication', title: 'Medication', lines: [], noDataText: 'No data in this period', charts: [] });
    expect(read.blocks[1]?.vessel).toEqual(overview.blocks[1]?.vessel);
    expect(read.blocks[2]?.lines).toEqual(['1 check-in', '1 of 7 days have data']);
    expect(read.notice).toBeUndefined();
  });

  it('passes on the notice of an account that has turned no domain on', async () => {
    const notice = 'Statistics appear here for the records you turned on in the chat.';
    serve({
      'POST /api/v1/session': [session('t')],
      'GET /api/v1/insights/overview?range=30': [
        { status: 200, body: { rangeDays: 30, rangeLabel: '15 Oct to 13 Nov', timeZone: 'UTC', ranges: [7, 30], blocks: [], notice } },
      ],
    });
    const api = createHttpApi(config());
    await api.open();

    const read = await api.client.insightsOverview(30);

    expect(read.blocks).toEqual([]);
    expect(read.notice).toBe(notice);
  });
});
