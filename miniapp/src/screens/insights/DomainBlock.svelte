<script lang="ts">
  import type { InsightBlock, TrackedDomain } from '../../api/types';
  import { useApp } from '../../app/context';
  import ChartFigure from '../../charts/ChartFigure.svelte';
  import Vessel from '../../charts/Vessel.svelte';
  import { t as en } from '../../i18n/index.svelte';
  import DomainGlyph from '../../ui/DomainGlyph.svelte';
  import Illustration from '../../ui/Illustration.svelte';

  let { block }: { block: InsightBlock } = $props();
  const { router } = useApp();

  /** Where the first record of a domain is made. */
  const START: Readonly<Record<TrackedDomain, string>> = {
    medication: '/meds',
    hydration: '/log/water',
    mood: '/log/mood/new',
    sleep: '/log/sleep',
    nutrition: '/log/food',
  };
</script>

<!-- One domain of the dashboard on its own panel: the figures in the server's words, the
     comparison with the user's own previous period (MA-INS-3), and the charts of the period, each
     with its own axis (small multiples, VA-CO-06). No score, no rating word (MA-INS-2). -->
<section class="section" aria-labelledby="insight-{block.id}" data-domain={block.id}>
  <div class="section-body pad block">
    <h2 class="title" id="insight-{block.id}" tabindex="-1">
      <DomainGlyph domain={block.id} size={22} />
      <span>{block.title}</span>
    </h2>
    {#if block.noDataText}
      <div class="empty">
        <Illustration kind={block.id} filled={0} />
        <p>{block.noDataText}</p>
        <a
          class="btn btn-tinted"
          href={router.hrefFor(START[block.id])}
          onclick={(event) => {
            event.preventDefault();
            void router.navigate(START[block.id]);
          }}>{en.insights.start[block.id]}</a
        >
      </div>
      {#if block.comparison}<p class="compare numeric">{block.comparison}</p>{/if}
    {:else}
      <div class="lead">
        {#if block.vessel}<Vessel {...block.vessel} />{/if}
        <div class="figures">
          {#each block.lines as line, index (index)}
            <p class="numeric" class:headline={index === 0} class:muted={index > 0}>{line}</p>
          {/each}
          {#if block.vessel}<p class="numeric">{block.vessel.label}</p>{/if}
        </div>
      </div>
      {#if block.comparison}<p class="compare numeric">{block.comparison}</p>{/if}
      {#each block.charts as chart, index (chart.id)}
        <div class="panel"><ChartFigure {chart} id="chart-{block.id}-{index}" /></div>
      {/each}
      {#if block.details && block.details.length > 0}
        <ul class="details">
          {#each block.details as line, index (index)}<li class="numeric">{line}</li>{/each}
        </ul>
      {/if}
      {#if block.medications && block.medications.length > 0}
        <div class="rows">
          <h3 class="subtitle">{en.insights.eachMedication}</h3>
          <ul class="plain" aria-label={en.insights.eachMedication}>
            {#each block.medications as row (row.id)}
              <li>
                <p class="numeric">{row.line}</p>
                {#if row.resolved > 0}
                  <!-- The share as a bar beside its counts; the counts are its text (VA-ME-02). -->
                  <div class="bar" aria-hidden="true">
                    <span style:inline-size="{(100 * row.taken) / row.resolved}%"></span>
                  </div>
                {/if}
              </li>
            {/each}
          </ul>
        </div>
      {/if}
    {/if}
  </div>
</section>

<style>
  .block {
    display: grid;
    gap: var(--dg-space-4);
  }

  .title {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
    font-size: var(--dg-text-title);
    font-weight: 600;
    line-height: 1.2;
  }

  .lead {
    display: flex;
    gap: var(--dg-space-4);
    align-items: center;
  }

  .figures {
    display: grid;
    gap: var(--dg-space-1);
  }

  .headline {
    font-size: var(--dg-text-body);
    font-weight: 600;
  }

  /* The bar beside the comparison wears the domain's hue: identity only, never a judgement. */
  .compare {
    padding: var(--dg-space-2) var(--dg-space-3);
    border-inline-start: 3px solid var(--dg-separator);
    color: var(--dg-text-muted);
    font-size: var(--dg-text-secondary);
  }

  [data-domain='medication'] .compare { border-inline-start-color: var(--dg-domain-medication); }
  [data-domain='hydration'] .compare { border-inline-start-color: var(--dg-domain-hydration); }
  [data-domain='mood'] .compare { border-inline-start-color: var(--dg-domain-mood); }
  [data-domain='sleep'] .compare { border-inline-start-color: var(--dg-domain-sleep); }
  [data-domain='nutrition'] .compare { border-inline-start-color: var(--dg-domain-nutrition); }

  /* Charts sit on a panel of their own, so their colours meet a validated background whatever the
     Telegram theme (VISUALS 3.7, VA-TG-02). */
  .panel {
    padding: var(--dg-space-3);
    border: 1px solid var(--dg-separator);
    border-radius: var(--dg-radius-section);
    background: var(--dg-surface-alt);
  }

  .empty {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .details {
    display: grid;
    gap: var(--dg-space-1);
    margin: 0;
    padding: 0;
    list-style: none;
    font-size: var(--dg-text-secondary);
  }

  .rows {
    display: grid;
    gap: var(--dg-space-2);
  }

  .subtitle {
    font-size: var(--dg-text-body);
    font-weight: 600;
  }

  .plain {
    display: grid;
    gap: var(--dg-space-3);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  .bar {
    block-size: 8px;
    margin-block-start: var(--dg-space-1);
    border-radius: 4px;
    background: var(--dg-separator);
    overflow: hidden;
  }

  .bar span {
    display: block;
    block-size: 100%;
    background: var(--dg-recorded);
  }
</style>
