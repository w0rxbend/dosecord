// The fixed words of the Rhythm view of Insights (MINI_APP 7.11; supervisor decision, 2026-11-28), in
// both shipped languages. They live apart from the catalogues so that they load with the Insights chunk,
// not with the initial JavaScript (MINI_APP 10.2); every other word of the view is the server's.
import { locale, type Locale } from './index.svelte';

interface RhythmWords {
  readonly tab: string;
  readonly loading: string;
  readonly table: string;
  readonly hour: string;
  readonly text: string;
}

const words: Readonly<Record<Locale, RhythmWords>> = {
  en: {
    tab: 'Rhythm',
    loading: 'Loading the rhythm',
    table: 'Records by hour',
    hour: 'Hour',
    text: 'Text of the rhythm',
  },
  uk: {
    tab: 'Ритм',
    loading: 'Завантаження ритму',
    table: 'Записи за годинами',
    hour: 'Година',
    text: 'Текст ритму',
  },
};

/** The words of the current language. */
export const rhythmWords = (): RhythmWords => words[locale()];
