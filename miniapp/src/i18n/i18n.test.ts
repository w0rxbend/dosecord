import { afterEach, describe, expect, it } from 'vitest';
import { languageHint, languageOf } from '../app/start';
import { en } from './en';
import { dateLocale, formatNumber, locale, localeOf, setLocale, t } from './index.svelte';
import { uk } from './uk';

/** Every leaf of a catalogue with its path: strings, string arrays, and functions called with sample arguments. */
function leaves(value: unknown, path = ''): Array<[string, unknown]> {
  if (typeof value === 'string') return [[path, value]];
  if (Array.isArray(value)) return value.map((item, index) => [`${path}[${index}]`, item] as [string, unknown]);
  if (typeof value === 'function') {
    const samples = ['3', '7', 'chat'];
    return [[`${path}()`, (value as (...args: unknown[]) => unknown)(...samples.slice(0, value.length))]];
  }
  if (typeof value === 'object' && value !== null) {
    return Object.entries(value).flatMap(([key, child]) => leaves(child, path ? `${path}.${key}` : key));
  }
  return [[path, value]];
}

const cyrillic = /\p{Script=Cyrillic}/u;

describe('the Ukrainian catalogue (ROADMAP G2.22)', () => {
  afterEach(() => setLocale('en'));

  it('has every English key, with the same arrays and functions', () => {
    expect(leaves(uk).map(([path]) => path)).toEqual(leaves(en).map(([path]) => path));
  });

  // The product name, and a label made only of its arguments ("Water, on"), are the same in every language.
  const sameInEveryLanguage = new Set(['app.name', 'log.open()']);

  it('translates every string, apart from the product name', () => {
    const english = new Map(leaves(en));
    const untranslated = leaves(uk).filter(
      ([path, text]) => !sameInEveryLanguage.has(path) && (typeof text !== 'string' || text === english.get(path) || !cyrillic.test(text)),
    );
    expect(untranslated).toEqual([]);
  });

  it('keeps the numbers of every template', () => {
    const numbers = (text: unknown) => String(text).match(/\d+/g)?.sort() ?? [];
    const english = new Map(leaves(en));
    const changed = leaves(uk).filter(([path, text]) => numbers(text).join() !== numbers(english.get(path)).join());
    expect(changed.map(([path]) => path)).toEqual([]);
  });

  it('chooses one, few and many forms', () => {
    expect(uk.meds.count(1)).toBe('1 позиція ліків');
    expect(uk.meds.count(3)).toBe('3 позиції ліків');
    expect(uk.meds.count(5)).toBe('5 позицій ліків');
    expect(uk.meds.count(21)).toBe('21 позиція ліків');
    expect(uk.insights.days(7)).toBe('7 днів');
    expect(uk.insights.days(2)).toBe('2 дні');
    expect(uk.reports.days(30)).toBe('Останні 30 днів');
  });

  it('writes numbers with a decimal comma', () => {
    expect(uk.today.waterTotal(1250, 2000)).toBe('1,3 л з 2,0 л (63%)');
    setLocale('uk');
    expect(formatNumber(1.5, 1)).toBe('1,5');
    setLocale('en');
    expect(formatNumber(1.5, 1)).toBe('1.5');
  });

  it('switches every string with the locale, and sets the document language', () => {
    expect(t.tabs.today).toBe('Today');
    setLocale('uk-UA');
    expect(locale()).toBe('uk');
    expect(t.tabs.today).toBe('Сьогодні');
    expect(document.documentElement.lang).toBe('uk');
    expect(dateLocale()).toBe('uk');
    setLocale('de');
    expect(t.tabs.today).toBe('Today');
  });

  it('maps language tags to the shipped locales', () => {
    expect(localeOf('uk')).toBe('uk');
    expect(localeOf('uk_UA')).toBe('uk');
    expect(localeOf('en-GB')).toBe('en');
    expect(localeOf(undefined)).toBe('en');
  });

  it('takes the account language from the bootstrap and the hint from the init data', () => {
    expect(languageOf({ timeZone: 'Europe/Kyiv', language: 'uk' })).toBe('uk');
    expect(languageOf({ timeZone: 'Europe/Kyiv' })).toBeUndefined();
    const user = encodeURIComponent(JSON.stringify({ id: 1, language_code: 'uk' }));
    expect(languageHint(`auth_date=1&user=${user}&hash=x`)).toBe('uk');
    expect(languageHint('auth_date=1')).toBeUndefined();
    expect(languageHint('user=%7Bnot-json')).toBeUndefined();
  });
});
