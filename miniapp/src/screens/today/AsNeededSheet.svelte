<script lang="ts">
  import { ApiError } from '../../api/client';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { loadAsNeeded, type AsNeededFact } from './as-needed';

  let { namesWithDoses }: { namesWithDoses: readonly string[] } = $props();
  const { api } = useApp();
  let open = $state(false);
  let loading = $state(false);
  let failed = $state(false);
  let facts = $state<AsNeededFact[]>([]);

  async function show(): Promise<void> {
    open = true;
    loading = true;
    failed = false;
    try {
      facts = await loadAsNeeded(api, namesWithDoses);
    } catch (cause) {
      if (!(cause instanceof ApiError)) throw cause;
      failed = true;
      facts = [];
    } finally {
      loading = false;
    }
  }

  function close(): void {
    open = false;
  }
</script>

<div class="section">
  <button type="button" class="btn btn-tinted" onclick={() => void show()}>{en.asNeeded.open}</button>
</div>

{#if open}
  <div class="section" role="dialog" aria-labelledby="as-needed-title">
    <div class="section-body pad sheet">
      <h2 id="as-needed-title">{en.asNeeded.title}</h2>
      {#if loading}
        <p role="status">{en.asNeeded.loading}</p>
      {:else if failed}
        <p role="alert">{en.asNeeded.notLoaded}</p>
        <button type="button" class="btn" onclick={() => void show()}>{en.common.tryAgain}</button>
      {:else if facts.length === 0}
        <p>{en.asNeeded.empty}</p>
      {:else}
        <ul>
          {#each facts as fact (fact.id)}
            <li aria-label={fact.name}>
              <p>{fact.name}</p>
              <p>{en.asNeeded.noReminders}</p>
              {#if fact.maxPerDay !== undefined}<p>{en.asNeeded.maxPerDay(fact.maxPerDay)}</p>{/if}
              {#if fact.minGapMinutes !== undefined}<p>{en.asNeeded.minGap(fact.minGapMinutes)}</p>{/if}
            </li>
          {/each}
        </ul>
      {/if}
      <button type="button" class="btn" onclick={close}>{en.asNeeded.close}</button>
    </div>
  </div>
{/if}

<style>
  .sheet {
    display: grid;
    gap: var(--dg-space-3);
  }

  h2 {
    font-size: var(--dg-text-title);
  }
</style>
