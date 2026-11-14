<script lang="ts">
  import type { InsightBlock } from '../../api/types';
  import ScenePainter from '../../charts/ScenePainter.svelte';
  import DomainGlyph from '../../ui/DomainGlyph.svelte';
  import { sentence } from './text';

  interface Props {
    block: InsightBlock;
    onOpen: (block: InsightBlock) => void;
  }

  let { block, onOpen }: Props = $props();
  const headline = $derived(sentence(block.lines[0] ?? block.noDataText ?? ''));
</script>

<!-- One stat tile of the dashboard (VISUALS 9.2): icon and name, the headline figure in the
     server's words, a small line of the period and the comparison with the user's own previous
     period. The line is decorative; the words carry the figures. The tile leads to its block. -->
<button type="button" class="tile" class:quiet={block.noDataText !== undefined} data-domain={block.id} onclick={() => onOpen(block)}>
  <span class="head">
    <DomainGlyph domain={block.id} size={18} />
    <span class="name">{block.title}</span>
  </span>
  <span class="value numeric">{headline}</span>
  {#if block.spark}
    <span class="spark"><ScenePainter scene={block.spark} label="" decorative /></span>
  {/if}
  <!-- Without a figure of its own, the tile does not repeat the previous period; its block does. -->
  {#if block.comparison && !block.noDataText}<span class="compare numeric">{block.comparison}</span>{/if}
</button>

<style>
  .tile {
    display: grid;
    align-content: start;
    gap: var(--dg-space-2);
    min-block-size: 132px;
    padding: var(--dg-space-3) var(--dg-space-4);
    border: 0;
    border-radius: var(--dg-radius-section);
    background: var(--dg-surface);
    text-align: start;
    cursor: pointer;
    transition: transform var(--dg-motion-press) ease-out;
  }

  .quiet {
    min-block-size: 0;
  }

  .quiet .value {
    color: var(--dg-text-muted);
    font-weight: 400;
  }

  .tile:active {
    transform: scale(0.98);
  }

  .head {
    display: flex;
    align-items: center;
    gap: var(--dg-space-2);
  }

  .name {
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }

  .value {
    font-weight: 600;
    line-height: 1.25;
  }

  .spark {
    display: block;
    inline-size: 100%;
    max-inline-size: 160px;
  }

  .compare {
    color: var(--dg-text-muted);
    font-size: var(--dg-text-caption);
    line-height: 1.3;
  }
</style>
