<script lang="ts">
  import { untrack } from 'svelte';
  import { t as en } from '../i18n/index.svelte';
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
  const scheduleEdit = () => import('../screens/schedule/ScheduleEditScreen.svelte');
  const insights = () => import('../screens/insights/InsightsScreen.svelte');
  const log = () => import('../screens/log/LogScreen.svelte');
  const water = () => import('../screens/water/WaterScreen.svelte');
  const waterSettings = () => import('../screens/water/WaterSettingsScreen.svelte');
  const mood = () => import('../screens/mood/MoodScreen.svelte');
  const moodNew = () => import('../screens/mood/MoodCheckInScreen.svelte');
  const sleep = () => import('../screens/sleep/SleepScreen.svelte');
  const food = () => import('../screens/food/FoodScreen.svelte');
  const reminders = () => import('../screens/reminders/RemindersScreen.svelte');
  const history = () => import('../screens/history/HistoryScreen.svelte');
  const settings = () => import('../screens/settings/SettingsScreen.svelte');
  const timeZone = () => import('../screens/settings/TimeZoneScreen.svelte');
  const quietHours = () => import('../screens/settings/QuietHoursScreen.svelte');
  const privacy = () => import('../screens/settings/PrivacyScreen.svelte');
  const reports = () => import('../screens/reports/ReportsScreen.svelte');
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
      {:else if route.screen === 'schedule-edit'}
        <Lazy load={scheduleEdit} props={{ id: route.id }} />
      {:else if route.screen === 'log'}
        <Lazy load={log} props={{}} />
      {:else if route.screen === 'water'}
        <Lazy load={water} props={{}} />
      {:else if route.screen === 'water-settings'}
        <Lazy load={waterSettings} props={{}} />
      {:else if route.screen === 'mood'}
        <Lazy load={mood} props={{}} />
      {:else if route.screen === 'mood-new'}
        <Lazy load={moodNew} props={{}} />
      {:else if route.screen === 'sleep'}
        <Lazy load={sleep} props={{}} />
      {:else if route.screen === 'food'}
        <Lazy load={food} props={{}} />
      {:else if route.screen === 'history'}
        <Lazy load={history} props={{}} />
      {:else if route.screen === 'day'}
        <TodayScreen date={route.date} title={en.day.title} />
      {:else if route.screen === 'settings'}
        <Lazy load={settings} props={{}} />
      {:else if route.screen === 'settings-tz'}
        <Lazy load={timeZone} props={{}} />
      {:else if route.screen === 'settings-notif'}
        <Lazy load={quietHours} props={{}} />
      {:else if route.screen === 'settings-privacy'}
        <Lazy load={privacy} props={{}} />
      {:else if route.screen === 'reminders'}
        <Lazy load={reminders} props={{}} />
      {:else if route.screen === 'reports'}
        <Lazy load={reports} props={{}} />
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
