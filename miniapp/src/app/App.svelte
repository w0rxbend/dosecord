<script lang="ts">
  import { untrack } from 'svelte';
  import { en } from '../i18n/en';
  import TodayScreen from '../screens/today/TodayScreen.svelte';
  import ToastHost from '../ui/ToastHost.svelte';
  import { provideApp, type AppContext } from './context';
  import Lazy from './Lazy.svelte';
  import { isTabRoot, tabOf } from './routes';
  import TabBar from './TabBar.svelte';

  let { context }: { context: AppContext } = $props();

  // The context is fixed for the life of the application.
  const app = untrack(() => context);
  const { router, tg, toasts } = app;
  provideApp(app);

  const route = $derived(router.route);
  const atRoot = $derived(isTabRoot(route));

  // BackButton is visible exactly when the screen is not a tab root (MA-NAV-2).
  $effect(() => {
    if (atRoot) return tg.backButton.hide();
    tg.backButton.show();
    return tg.backButton.onClick(() => void router.back());
  });

  const medList = () => import('../screens/meds/MedListScreen.svelte');
  const medEditor = () => import('../screens/editor/MedEditorScreen.svelte');
  const schedule = () => import('../screens/schedule/ScheduleScreen.svelte');
  const insights = () => import('../screens/insights/InsightsScreen.svelte');
</script>

<div class="app" class:with-tabs={atRoot}>
  {#if router.unmatched}
    <p class="banner" role="status">
      {en.common.unmatchedRoute}
      <button type="button" class="btn" onclick={() => (router.unmatched = false)}>{en.common.dismiss}</button>
    </p>
  {/if}

  <!-- A failing screen is replaced on its own; the tab bar keeps working (MINI_APP 8.3). -->
  <svelte:boundary>
    {#key JSON.stringify(route)}
      {#if route.screen === 'today'}
        <TodayScreen date={route.date} />
      {:else if route.screen === 'meds'}
        <Lazy load={medList} props={{}} />
      {:else if route.screen === 'med-new'}
        <Lazy load={medEditor} props={{}} />
      {:else if route.screen === 'med-edit'}
        <Lazy load={medEditor} props={{ id: route.id }} />
      {:else if route.screen === 'schedule-new'}
        <Lazy load={schedule} props={{ medicationId: route.medicationId }} />
      {:else}
        <Lazy load={insights} props={{}} />
      {/if}
    {/key}
    {#snippet failed(_error, reset)}
      <main class="banner" role="alert">
        <h1>{en.common.screenFailed}</h1>
        <button type="button" class="btn btn-tinted" onclick={reset}>{en.common.reloadScreen}</button>
      </main>
    {/snippet}
  </svelte:boundary>

  {#if atRoot}<TabBar active={tabOf(route)} />{/if}
  <ToastHost {toasts} />
</div>

<style>
  .app {
    --dg-tabbar: 56px;
    min-block-size: var(--dg-stable-height);
    padding: var(--dg-inset-top) var(--dg-inset-right) var(--dg-inset-bottom) var(--dg-inset-left);
  }

  .with-tabs {
    padding-block-end: calc(var(--dg-tabbar) + var(--dg-inset-bottom) + var(--dg-space-4));
  }

  .banner {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--dg-space-2);
    max-inline-size: var(--dg-content-max);
    margin-inline: auto;
    padding: var(--dg-space-2) var(--dg-space-4);
  }

  h1 {
    font-size: var(--dg-text-title);
  }
</style>
