import { describe, expect, it } from 'vitest';
import { dayPath, isTabRoot, parentPath, parsePath, pathFromStartParam, tabOf } from './routes';

describe('parsePath', () => {
  it.each([
    ['/', { screen: 'today' }],
    ['/day/20261112', { screen: 'today', date: '2026-11-12' }],
    ['/meds', { screen: 'meds' }],
    ['/meds/new', { screen: 'med-new' }],
    ['/meds/med-a/edit', { screen: 'med-edit', id: 'med-a' }],
    ['/meds/med-a/schedules/new', { screen: 'schedule-new', medicationId: 'med-a' }],
    ['/insights', { screen: 'insights' }],
    ['/settings/reminders', { screen: 'reminders' }],
    ['/setup', { screen: 'setup' }],
    ['/settings/day', { screen: 'settings-day' }],
  ])('maps %s to its screen', (path, route) => {
    expect(parsePath(path)).toEqual(route);
  });

  it.each(['/nope', '/meds/', '/meds/a b/edit', '/day/2026-11-12', '/meds/<script>/edit'])(
    'rejects %s',
    (path) => {
      expect(parsePath(path)).toBeNull();
    },
  );
});

describe('setup and the day profile', () => {
  it('opens set-up under Today and the day profile under Settings, neither a tab root', () => {
    expect(tabOf({ screen: 'setup' })).toBe('today');
    expect(parentPath({ screen: 'setup' })).toBe('/');
    expect(parentPath({ screen: 'settings-day' })).toBe('/settings');
    expect(isTabRoot({ screen: 'setup' })).toBe(false);
  });
});

describe('pathFromStartParam', () => {
  it.each([
    ['today', '/'],
    ['today_20261112', '/day/20261112'],
    ['meds', '/meds'],
    ['mednew', '/meds/new'],
    ['medit_med-a', '/meds/med-a/edit'],
    ['schednew_med-a', '/meds/med-a/schedules/new'],
    ['insights', '/insights'],
    ['log', '/log'],
    ['water', '/log/water'],
    ['mood', '/log/mood'],
    ['moodnew', '/log/mood/new'],
    ['sleep', '/log/sleep'],
    ['food', '/log/food'],
    ['hist', '/insights/history'],
    ['hist_20261112', '/insights/history/20261112'],
    ['settings', '/settings'],
    ['set_tz', '/settings/tz'],
    ['set_privacy', '/settings/privacy'],
    ['reminders', '/settings/reminders'],
    ['setup', '/setup'],
    ['set_day', '/settings/day'],
  ])('maps %s to %s', (param, path) => {
    expect(pathFromStartParam(param)).toBe(path);
  });

  // `photos` names a screen that is not enabled (MA-ROUTE-2).
  it.each(['', 'photos', 'water_1', 'medit_', 'medit_a/b', 'today_2026', 'hist_2026', '../meds'])('rejects %j', (param) => {
    expect(pathFromStartParam(param)).toBeNull();
  });
});

describe('navigation structure', () => {
  it('treats the three tab screens as roots and the task screens as children of Meds', () => {
    expect(isTabRoot({ screen: 'meds' })).toBe(true);
    expect(isTabRoot({ screen: 'med-new' })).toBe(false);
    expect(parentPath({ screen: 'schedule-new', medicationId: 'med-a' })).toBe('/meds');
  });

  it('puts the tracking screens under Log and the calendar and day detail under Insights', () => {
    expect(isTabRoot({ screen: 'log' })).toBe(true);
    expect(tabOf({ screen: 'mood-new' })).toBe('log');
    expect(parentPath({ screen: 'mood-new' })).toBe('/log/mood');
    expect(parentPath({ screen: 'water-settings' })).toBe('/log/water');
    expect(tabOf({ screen: 'day', date: '2026-11-12' })).toBe('insights');
    expect(parentPath({ screen: 'day', date: '2026-11-12' })).toBe('/insights/history');
    expect(isTabRoot({ screen: 'settings' })).toBe(false);
    expect(parentPath({ screen: 'settings-tz' })).toBe('/settings');
  });

  it('writes a day path without separators', () => {
    expect(dayPath('2026-11-12')).toBe('/day/20261112');
  });
});

describe('schedule edit route', () => {
  it('maps sched_<id> to the schedule path and the path to the edit screen under Meds', () => {
    expect(pathFromStartParam('sched_sch-a')).toBe('/schedules/sch-a');
    const route = parsePath('/schedules/sch-a');
    expect(route).toEqual({ screen: 'schedule-edit', id: 'sch-a' });
    expect(route && tabOf(route)).toBe('meds');
    expect(route && parentPath(route)).toBe('/meds');
  });
});
