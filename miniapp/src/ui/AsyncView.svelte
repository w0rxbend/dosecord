<script lang="ts" generics="T">
  import type { Snippet } from 'svelte';
  import type { Resource } from '../state/resource.svelte';
  import ErrorState from './ErrorState.svelte';
  import Skeleton from './Skeleton.svelte';

  interface Props {
    resource: Resource<T>;
    loadingLabel: string;
    skeletonRows?: number;
    children: Snippet<[T]>;
  }

  let { resource, loadingLabel, skeletonRows = 3, children }: Props = $props();
</script>

<!-- Loaded data stays on screen while it refreshes; an error replaces only missing content. -->
{#if resource.data !== undefined}
  {@render children(resource.data)}
{:else if resource.error}
  <ErrorState error={resource.error} onRetry={() => resource.load()} />
{:else}
  <Skeleton label={loadingLabel} rows={skeletonRows} />
{/if}
