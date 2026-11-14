<script lang="ts" generics="P extends Record<string, unknown>">
  import type { Component } from 'svelte';
  import { ApiError } from '../api/client';
  import { t as en } from '../i18n/index.svelte';
  import ErrorState from '../ui/ErrorState.svelte';
  import Skeleton from '../ui/Skeleton.svelte';

  interface Props {
    load: () => Promise<{ default: Component<P> }>;
    props: P;
  }

  let { load, props }: Props = $props();
  let attempt = $state(0);

  // Reading `attempt` makes "Try again" request the chunk once more.
  const pending = $derived.by(() => {
    void attempt;
    return load();
  });
</script>

<!-- Each tab and task screen is its own lazily loaded chunk (MA-PERF-1). -->
{#await pending}
  <main class="frame"><Skeleton label={en.common.loadingScreen} rows={4} /></main>
{:then module}
  <module.default {...props} />
{:catch}
  <main class="frame"><ErrorState error={new ApiError('network')} onRetry={() => (attempt += 1)} /></main>
{/await}

<style>
  .frame {
    max-inline-size: var(--dg-content-max);
    margin-inline: auto;
    padding: var(--dg-space-4);
  }
</style>
