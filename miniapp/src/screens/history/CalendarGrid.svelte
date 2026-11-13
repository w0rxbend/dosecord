<script lang="ts">
  import { tick } from 'svelte';
  import type { BlobAsset, InsightCalendar } from '../../api/types';
  import { findState } from '../../charts/blob';
  import { en } from '../../i18n/en';

  interface Props {
    calendar: InsightCalendar;
    showValues: boolean;
    scheme: 'light' | 'dark';
    asset?: BlobAsset;
    onOpen: (date: string) => void;
    onMonth: (month: string) => void;
  }

  let { calendar, showValues, scheme, asset, onOpen, onMonth }: Props = $props();
  let grid: HTMLDivElement;

  const isoWeekday = (date: string): number => new Date(`${date}T00:00:00Z`).getUTCDay() || 7;

  // Week start follows the account; full weekday names for assistive technology, two letters on
  // screen (MA-HIST-6).
  const order = $derived(Array.from({ length: 7 }, (_, index) => ((calendar.weekStart - 1 + index) % 7) + 1));
  const lead = $derived(calendar.days.length === 0 ? 0 : (isoWeekday(calendar.days[0]!.date) - calendar.weekStart + 7) % 7);
  const lastPast = $derived(calendar.days.reduce((last, day, index) => (day.swatch.state === 'future' ? last : index), 0));
  let focused = $state(0);
  $effect(() => {
    focused = Math.max(0, lastPast);
  });

  /** The ramp is lightness of one domain colour; mood takes the blob's colour for its level (MA-HIST-3). */
  function alphaOf(step: number | undefined): string {
    return step === undefined ? '0.3' : String(0.15 + (0.45 * step) / calendar.steps);
  }

  /** The blob's own fill for the level, and its face ink, which is chosen to read on that fill. */
  function moodColours(blobState: string | undefined): { fill?: string; ink?: string } {
    const state = blobState && asset ? findState(asset, blobState) : undefined;
    if (!state) return {};
    return { fill: scheme === 'dark' ? state.dark.fillOuter : state.light.fillOuter, ink: state.face?.ink };
  }

  async function move(event: KeyboardEvent): Promise<void> {
    const moves: Record<string, number> = {
      ArrowLeft: focused - 1,
      ArrowRight: focused + 1,
      ArrowUp: focused - 7,
      ArrowDown: focused + 7,
      Home: 0,
      End: calendar.days.length - 1,
    };
    if (event.key === 'PageUp' || event.key === 'PageDown') {
      event.preventDefault();
      const month = event.key === 'PageUp' ? calendar.previousMonth : calendar.nextMonth;
      if (month) onMonth(month);
      return;
    }
    const next = moves[event.key];
    if (next === undefined) return;
    event.preventDefault();
    focused = Math.max(0, Math.min(calendar.days.length - 1, next));
    await tick();
    grid.querySelector<HTMLButtonElement>(`[data-index="${focused}"]`)?.focus();
  }
</script>

<!-- The grid of one month. Each past day is a button over its whole grid track, named with the
     date and the value in words (MA-HIST-1); arrow keys, Home, End, Page Up and Page Down move
     (MA-HIST-2). "Nothing scheduled", "no entry" and future days are not steps of the ramp
     (MA-HIST-4). -->
<div class="grid" role="group" aria-label={calendar.monthLabel} bind:this={grid}>
  {#each order as weekday (weekday)}
    <abbr class="weekday muted" title={en.history.weekdays[weekday - 1]}>{en.history.weekdays[weekday - 1]?.slice(0, 2)}</abbr>
  {/each}
  {#each Array.from({ length: lead }, (_, index) => index) as blank (blank)}<span></span>{/each}
  {#each calendar.days as day, index (day.date)}
    <button
      type="button"
      class="cell {day.swatch.state} {calendar.domain}"
      data-index={index}
      tabindex={index === focused ? 0 : -1}
      aria-label={day.name}
      disabled={day.swatch.state === 'future'}
      style:--cell-alpha={alphaOf(day.swatch.step)}
      style:--cell-colour={calendar.domain === 'mood' ? moodColours(day.swatch.blobState).fill : undefined}
      style:--cell-ink={calendar.domain === 'mood' ? moodColours(day.swatch.blobState).ink : undefined}
      onclick={() => onOpen(day.date)}
      onkeydown={move}
      onfocus={() => (focused = index)}
    >
      <span class="number numeric" aria-hidden="true">{Number(day.date.slice(8))}</span>
      {#if showValues && day.value}<span class="value numeric" aria-hidden="true">{day.value}</span>{/if}
      {#if day.swatch.hasNoRecord}<span class="no-record" aria-hidden="true">?</span>{/if}
    </button>
  {/each}
</div>

<style>
  .grid {
    display: grid;
    grid-template-columns: repeat(7, 1fr);
    gap: 2px;
  }

  .weekday {
    text-align: center;
    text-decoration: none;
    padding-block: var(--dg-space-1);
  }

  .cell {
    position: relative;
    display: grid;
    place-items: center;
    align-content: center;
    min-block-size: var(--dg-target);
    padding: 0;
    border: 0;
    border-radius: var(--dg-radius-control);
    background: transparent;
    color: var(--dg-text);
    cursor: pointer;
    isolation: isolate;
  }

  .cell.value::before {
    content: '';
    position: absolute;
    inset: 0;
    z-index: -1;
    border-radius: inherit;
    background: var(--cell-colour, var(--dg-domain-medication));
    opacity: var(--cell-alpha);
  }

  .cell.value.hydration::before {
    background: var(--dg-domain-hydration);
  }

  .cell.value.mood {
    color: var(--cell-ink, var(--dg-text));
  }

  .cell.value.mood::before {
    background: var(--cell-colour, var(--dg-domain-mood));
    opacity: 1;
  }

  .cell.empty {
    border: 1px dashed var(--dg-text-muted);
    color: var(--dg-text-muted);
  }

  .cell.future {
    color: var(--dg-text-muted);
    cursor: default;
    opacity: 0.6;
  }

  .number {
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }

  .value {
    font-size: var(--dg-text-caption);
  }

  .no-record {
    position: absolute;
    inset-block-start: 2px;
    inset-inline-end: 4px;
    font-size: var(--dg-text-caption);
    font-weight: 700;
  }
</style>
