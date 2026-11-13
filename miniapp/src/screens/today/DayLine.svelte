<script lang="ts">
  import type { DayLineMark } from '../../api/types';

  let { marks, nowMinute }: { marks: readonly DayLineMark[]; nowMinute?: number } = $props();

  // One unit is five minutes, so the 24 hours span 288 units.
  const WIDTH = 288;
  const LINE_Y = 18;
  const HOURS = [0, 6, 12, 18, 24];
  const x = (minute: number): number => minute / 5;
  const doses = $derived(marks.filter((mark) => mark.kind === 'dose'));
  const water = $derived(marks.filter((mark) => mark.kind === 'water'));
  const isRecorded = (mark: DayLineMark): boolean => mark.state === 'taken' || mark.state === 'taken_late';
</script>

<!-- The day line: doses are marks on a 24-hour line, water entries are ticks below it, the
     present moment is a hairline. A decorative duplicate of the lists below it (MA-TODAY-7). -->
<svg class="day-line" viewBox="-8 0 {WIDTH + 16} 56" aria-hidden="true">
  <line x1="0" y1={LINE_Y} x2={WIDTH} y2={LINE_Y} class="axis" />
  {#each HOURS as hour (hour)}
    <line x1={x(hour * 60)} y1={LINE_Y - 4} x2={x(hour * 60)} y2={LINE_Y + 4} class="axis" />
    <text x={x(hour * 60)} y="50" text-anchor="middle">{String(hour).padStart(2, '0')}</text>
  {/each}
  {#each water as mark, index (index)}
    <line x1={x(mark.minute)} y1={LINE_Y + 8} x2={x(mark.minute)} y2={LINE_Y + 16} class="water" />
  {/each}
  {#each doses as mark, index (index)}
    <circle
      cx={x(mark.minute)}
      cy={LINE_Y}
      r="5"
      class="dose"
      class:recorded={isRecorded(mark)}
      class:due={mark.state === 'due'}
    />
  {/each}
  {#if nowMinute !== undefined}
    <line x1={x(nowMinute)} y1="2" x2={x(nowMinute)} y2={LINE_Y + 18} class="now" />
  {/if}
</svg>

<style>
  .day-line {
    display: block;
    inline-size: 100%;
    block-size: auto;
  }

  .axis {
    stroke: var(--dg-separator);
    stroke-width: 1.5;
  }

  text {
    fill: var(--dg-text-muted);
    font-size: 13px;
    font-variant-numeric: tabular-nums;
  }

  .water {
    stroke: var(--dg-domain-hydration);
    stroke-width: 2;
    stroke-linecap: round;
  }

  .dose {
    fill: var(--dg-surface);
    stroke: var(--dg-domain-medication);
    stroke-width: 2;
  }

  .dose.recorded {
    fill: var(--dg-recorded);
    stroke: var(--dg-recorded);
  }

  .dose.due {
    fill: var(--dg-accent);
    stroke: var(--dg-accent);
  }

  .now {
    stroke: var(--dg-text);
    stroke-width: 1;
  }
</style>
