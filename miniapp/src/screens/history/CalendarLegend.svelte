<script lang="ts">
  import type { BlobAsset, InsightCalendar } from '../../api/types';
  import { findState } from '../../charts/blob';
  import { en } from '../../i18n/en';

  interface Props {
    calendar: InsightCalendar;
    scheme: 'light' | 'dark';
    asset?: BlobAsset;
    showValues: boolean;
  }

  let { calendar, scheme, asset, showValues = $bindable() }: Props = $props();

  /** The legend of mood shows the blob's colour for each level, as the cells do. */
  function moodFill(blobState: string | undefined): string | undefined {
    const state = asset && blobState ? findState(asset, blobState) : undefined;
    return state && (scheme === 'dark' ? state.dark.fillOuter : state.light.fillOuter);
  }
</script>

<!-- The legend in words, and "Show values in cells", so the calendar reads without colour (MA-HIST-3). -->
<section class="section" aria-labelledby="history-legend">
  <h2 class="section-title" id="history-legend">{en.history.legend}</h2>
  <div class="section-body pad legend">
    <ul>
      {#each calendar.legend as entry (entry.label)}
        <li>
          <span
            class="swatch {entry.swatch.state} {calendar.domain}"
            style:--cell-alpha={String(0.15 + (0.45 * (entry.swatch.step ?? 0)) / calendar.steps)}
            style:--cell-colour={moodFill(entry.swatch.blobState)}
            aria-hidden="true">{entry.swatch.hasNoRecord ? '?' : ''}</span
          >{entry.label}
        </li>
      {/each}
    </ul>
    <label class="toggle">
      <input type="checkbox" role="switch" bind:checked={showValues} />
      <span>{en.history.showValues}</span>
    </label>
  </div>
</section>

<style>
  .legend {
    display: grid;
    gap: var(--dg-space-3);
  }

  ul {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-2) var(--dg-space-4);
    font-size: var(--dg-text-secondary);
  }

  li {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
  }

  .swatch {
    position: relative;
    isolation: isolate;
    display: inline-grid;
    place-items: center;
    inline-size: 20px;
    block-size: 20px;
    border-radius: 4px;
    font-size: var(--dg-text-caption);
    font-weight: 700;
  }

  .swatch.value::before {
    content: '';
    position: absolute;
    inset: 0;
    z-index: -1;
    border-radius: inherit;
    background: var(--dg-domain-medication);
    opacity: var(--cell-alpha);
  }

  .swatch.value.hydration::before {
    background: var(--dg-domain-hydration);
  }

  .swatch.value.sleep::before {
    background: var(--dg-domain-sleep);
  }

  .swatch.value.mood::before {
    background: var(--cell-colour, var(--dg-domain-mood));
    opacity: 1;
  }

  .swatch.empty {
    border: 1px dashed var(--dg-text-muted);
  }

  .toggle {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    min-block-size: var(--dg-target);
  }

  .toggle input {
    inline-size: 24px;
    block-size: 24px;
  }
</style>
