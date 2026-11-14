<script lang="ts">
  import type { Snippet } from 'svelte';
  import { en } from '../../i18n/en';

  interface Props {
    number: number;
    title: string;
    done: boolean;
    children: Snippet;
  }

  let { number, title, done, children }: Props = $props();
</script>

<!-- One step of set-up: its number in a cell that is filled when done (the cell of VISUALS 3.1),
     its state in words, and its content. -->
<li class="section-body pad step">
  <div class="head">
    <span class="cell numeric" class:done aria-hidden="true">
      {#if done}
        <svg viewBox="0 0 24 24" width="16" height="16" focusable="false"><path d="M6 12.5l4 4 8-9" /></svg>
      {:else}
        {number}
      {/if}
    </span>
    <h2 id="setup-step-{number}" class="title">{title}</h2>
    <span class="state" class:done>{done ? en.setup.done : en.setup.todo}</span>
  </div>
  <div class="body">{@render children()}</div>
</li>

<style>
  .step {
    display: grid;
    gap: var(--dg-space-3);
  }

  .head {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
  }

  .cell {
    display: inline-grid;
    place-items: center;
    inline-size: 32px;
    block-size: 32px;
    flex: none;
    border: 1.5px dashed var(--dg-text-muted);
    border-radius: 9px;
    color: var(--dg-text-muted);
    font-weight: 600;
  }

  .cell.done {
    border: 0;
    background: var(--dg-recorded);
  }

  .cell svg {
    fill: none;
    stroke: var(--dg-surface);
    stroke-width: 2.5;
    stroke-linecap: round;
    stroke-linejoin: round;
  }

  .title {
    flex: 1;
    font-size: var(--dg-text-body);
    font-weight: 600;
  }

  .state {
    color: var(--dg-text-muted);
    font-size: var(--dg-text-secondary);
  }

  .state.done {
    color: var(--dg-text);
  }

  .body {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }
</style>
