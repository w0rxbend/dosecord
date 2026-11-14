// The message catalogue of the account's language (MINI_APP 5.7 rule 8, MA-SET-2; ROADMAP G2.22).
// `t` reads the catalogue of the current locale on every access, and the locale is reactive state, so a
// template that reads `t.x` renders again when the language changes, without a reload. Components keep
// the name they used before localisation: `import { t as en } from '../i18n'`.
import type { DoseState } from '../api/types';
import { en } from './en';
import { uk } from './uk';

export type Locale = 'en' | 'uk';
type Catalogue = typeof en;

const catalogues: Readonly<Record<Locale, Catalogue>> = { en, uk };

let current = $state<Locale>('en');

/** The shipped locale a stored code or a client's language tag names: `uk`, `uk-UA` and `uk_UA` are Ukrainian. */
export function localeOf(code: string | undefined | null): Locale {
  const primary = (code ?? '').trim().toLowerCase().split(/[-_]/)[0];
  return primary === 'uk' ? 'uk' : 'en';
}

export function locale(): Locale {
  return current;
}

/** Switches every visible string and the document's `lang` (MA-SET-2). Both shipped locales are left to right. */
export function setLocale(code: string | undefined | null): void {
  current = localeOf(code);
  if (typeof document !== 'undefined') {
    document.documentElement.lang = current;
    document.documentElement.dir = 'ltr';
  }
}

export const t: Catalogue = new Proxy({} as Catalogue, {
  get: (_target, key) => Reflect.get(catalogues[current], key),
});

const DOSE_TIMED = {
  pending: 'scheduledTime',
  snoozed: 'snoozedUntil',
  taken: 'resolvedTime',
  taken_late: 'resolvedTime',
} as const;

/** The word for a dose state, with its time where the state has one (MINI_APP 5.4). */
export function doseStatusText(occurrence: {
  state: DoseState;
  scheduledTime: string;
  resolvedTime?: string;
  snoozedUntil?: string;
  dueForMinutes?: number;
}): string {
  const { state } = occurrence;
  if (state === 'due') return t.dose.due(occurrence.dueForMinutes ?? 0);
  if (state === 'skipped' || state === 'missed' || state === 'unknown') return t.dose[state];
  return t.dose[state](occurrence[DOSE_TIMED[state]] ?? occurrence.scheduledTime);
}

export function errorText(code: string): string {
  return (t.errors as Readonly<Record<string, string>>)[code] ?? t.errors.server;
}

export function fieldErrorText(code: string): string {
  return t.fieldErrors[code] ?? t.errors.validation;
}

/** The BCP 47 tag dates and times are formatted with: 24-hour clock in both locales (PRODUCT_SPEC R-LOC-5). */
export function dateLocale(): string {
  return current === 'uk' ? 'uk' : 'en-GB';
}

/** A number in the current locale: "1.5" and "1,5" (PRODUCT_SPEC R-LOC-4). */
export function formatNumber(value: number, fractionDigits = 0): string {
  return new Intl.NumberFormat(current, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(value);
}
