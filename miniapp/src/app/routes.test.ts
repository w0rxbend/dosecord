import { describe, expect, it } from 'vitest';
import { dayPath, isTabRoot, parentPath, parsePath, pathFromStartParam } from './routes';

describe('parsePath', () => {
  it.each([
    ['/', { screen: 'today' }],
    ['/day/20261112', { screen: 'today', date: '2026-11-12' }],
    ['/meds', { screen: 'meds' }],
    ['/meds/new', { screen: 'med-new' }],
    ['/meds/med-a/edit', { screen: 'med-edit', id: 'med-a' }],
    ['/meds/med-a/schedules/new', { screen: 'schedule-new', medicationId: 'med-a' }],
    ['/insights', { screen: 'insights' }],
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

describe('pathFromStartParam', () => {
  it.each([
    ['today', '/'],
    ['today_20261112', '/day/20261112'],
    ['meds', '/meds'],
    ['mednew', '/meds/new'],
    ['medit_med-a', '/meds/med-a/edit'],
    ['schednew_med-a', '/meds/med-a/schedules/new'],
    ['insights', '/insights'],
  ])('maps %s to %s', (param, path) => {
    expect(pathFromStartParam(param)).toBe(path);
  });

  it.each(['', 'water', 'medit_', 'medit_a/b', 'today_2026', '../meds'])('rejects %j', (param) => {
    expect(pathFromStartParam(param)).toBeNull();
  });
});

describe('navigation structure', () => {
  it('treats the three tab screens as roots and the task screens as children of Meds', () => {
    expect(isTabRoot({ screen: 'meds' })).toBe(true);
    expect(isTabRoot({ screen: 'med-new' })).toBe(false);
    expect(parentPath({ screen: 'schedule-new', medicationId: 'med-a' })).toBe('/meds');
  });

  it('writes a day path without separators', () => {
    expect(dayPath('2026-11-12')).toBe('/day/20261112');
  });
});
