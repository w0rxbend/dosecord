<script lang="ts">
  import { onMount, type Snippet } from 'svelte';
  import { t as en } from '../i18n/index.svelte';

  interface Props {
    title: string;
    subtitle?: string;
    actions?: Snippet;
    children: Snippet;
  }

  let { title, subtitle, actions, children }: Props = $props();
  let heading: HTMLHeadingElement;

  $effect(() => {
    document.title = `${title} | ${en.app.name}`;
  });

  // A route change moves focus to the screen's h1 (MA-A11Y-4).
  onMount(() => heading.focus());
</script>

<main class="screen">
  <header>
    <div>
      <h1 tabindex="-1" bind:this={heading}>{title}</h1>
      {#if subtitle}<p class="muted">{subtitle}</p>{/if}
    </div>
    {#if actions}<div class="actions">{@render actions()}</div>{/if}
  </header>
  {@render children()}
</main>

<style>
  .screen {
    max-inline-size: var(--dg-content-max);
    margin-inline: auto;
    padding: var(--dg-space-4);
  }

  header {
    display: flex;
    flex-wrap: wrap;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--dg-space-2);
    margin-block-end: var(--dg-space-4);
  }

  h1 {
    font-size: var(--dg-text-screen);
    font-weight: 700;
    line-height: 1.2;
  }

  .actions {
    display: flex;
    gap: var(--dg-space-2);
  }
</style>
