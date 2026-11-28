import { describe, expect, it } from 'vitest';
import { lastNightDraft } from './night';

// Device-local wall clock: the form reads and writes the device's own times (MA-SLEEP-6).
const at = (date: string, time: string): Date => new Date(`${date}T${time}:00`);

describe('the night the sleep form starts from', () => {
  it('is 23:00 the evening before to 07:00 once 07:00 has passed', () => {
    expect(lastNightDraft('2026-11-28', at('2026-11-28', '09:30'))).toEqual({
      kind: 'night',
      onsetDate: '2026-11-27',
      onsetTime: '23:00',
      wakeDate: '2026-11-28',
      wakeTime: '07:00',
    });
  });

  it('ends now, to the 5 minutes before, while it is earlier than 07:00, so it is never in the future', () => {
    const draft = lastNightDraft('2026-11-28', at('2026-11-28', '03:17'));
    expect(draft.wakeDate).toBe('2026-11-28');
    expect(draft.wakeTime).toBe('03:15');
    expect(draft.onsetDate).toBe('2026-11-27');
  });

  it('keeps 07:00 for a day that is not the device date', () => {
    expect(lastNightDraft('2026-11-12', at('2026-11-28', '03:17')).wakeTime).toBe('07:00');
  });
});
