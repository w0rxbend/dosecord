// What set-up reads and how each step counts as done. Every state comes from the server's
// records, so nothing about the person is kept on the device (MINI_APP 6.6); the one exception is
// "Looks right" for the time zone, which lasts for the session.
import { unlessOff, type ApiClient } from '../../api/client';
import { OFF, type AccountSettings, type Consents, type DayProfile, type MedicationSummary, type WaterSettings } from '../../api/types';

export interface SetupState {
  readonly consents: Consents;
  readonly account: AccountSettings;
  readonly day: DayProfile;
  /** Absent when the medication consent is off. */
  readonly medications?: readonly MedicationSummary[];
  /** Absent when the water consent is off. */
  readonly water?: WaterSettings;
}

export async function readSetup(api: ApiClient): Promise<SetupState> {
  const [consents, account, reminders, medications, water] = await Promise.all([
    api.consents(),
    api.settings(),
    api.reminders(),
    unlessOff(api.medications()),
    unlessOff(api.waterSettings()),
  ]);
  return {
    consents,
    account,
    day: reminders.day,
    medications: medications === OFF ? undefined : medications,
    water: water === OFF ? undefined : water,
  };
}

export type StepId = 'consent' | 'zone' | 'day' | 'first';

/** A day profile the person never saved is the product's default; the server says which. */
export const daySet = (day: DayProfile): boolean => day.saved === true;

/** "Looks right" for the time zone, kept in memory for the life of the session only. */
let confirmedZone: string | undefined;

export function confirmZone(zone: string): void {
  confirmedZone = zone;
}

export function isZoneConfirmed(zone: string): boolean {
  return confirmedZone === zone;
}

export function stepsDone(state: SetupState, zoneConfirmed: boolean, deviceZone: string | undefined): Record<StepId, boolean> {
  return {
    consent: state.consents.items.some((item) => item.granted),
    zone: zoneConfirmed || deviceZone === undefined || deviceZone === state.account.timeZone,
    day: daySet(state.day),
    first: (state.medications?.length ?? 0) > 0 || state.water?.goalMl !== undefined,
  };
}
