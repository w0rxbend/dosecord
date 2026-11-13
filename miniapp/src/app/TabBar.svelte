<script lang="ts">
  import { en } from '../i18n/en';
  import { useApp } from './context';
  import type { TabId } from './routes';

  let { active }: { active: TabId } = $props();

  const { router, tg } = useApp();

  // Four destinations; the tracking domains sit behind Log (MINI_APP 4.1).
  const TABS: readonly { id: TabId; path: string; label: string; icon: string }[] = [
    { id: 'today', path: '/', label: en.tabs.today, icon: 'M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16zM12 8v4l3 2' },
    { id: 'meds', path: '/meds', label: en.tabs.meds, icon: 'M7 17a4.2 4.2 0 0 1 0-6l4-4a4.2 4.2 0 0 1 6 6l-4 4a4.2 4.2 0 0 1-6 0zM9 9l6 6' },
    { id: 'log', path: '/log', label: en.tabs.log, icon: 'M5 5h14M5 12h14M5 19h9M17 16v6M14 19h6' },
    { id: 'insights', path: '/insights', label: en.tabs.insights, icon: 'M5 20V11M12 20V4M19 20v-6' },
  ];

  function open(event: MouseEvent, path: string): void {
    event.preventDefault();
    tg.haptic('impact-light');
    void router.replace(path);
  }
</script>

<!-- A nav with links and aria-current, not a tablist (MINI_APP 5.6). -->
<nav aria-label={en.tabs.label}>
  <ul>
    {#each TABS as tab (tab.id)}
      <li>
        <a
          href={router.hrefFor(tab.path)}
          aria-current={tab.id === active ? 'page' : undefined}
          onclick={(event) => open(event, tab.path)}
        >
          <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d={tab.icon} /></svg>
          {tab.label}
        </a>
      </li>
    {/each}
  </ul>
</nav>

<style>
  /* Pinned to the stable viewport height, never to 100vh (MA-LAYOUT-1). */
  nav {
    position: fixed;
    inset-inline: 0;
    inset-block-start: calc(var(--dg-stable-height) - var(--dg-tabbar) - var(--dg-inset-bottom));
    block-size: calc(var(--dg-tabbar) + var(--dg-inset-bottom));
    padding-block-end: var(--dg-inset-bottom);
    border-block-start: 1px solid var(--dg-separator);
    background: var(--dg-bottom-bar);
  }

  ul {
    display: flex;
    max-inline-size: var(--dg-content-max);
    block-size: 100%;
    margin-inline: auto;
  }

  li {
    flex: 1;
  }

  a {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--dg-space-1);
    block-size: 100%;
    min-inline-size: var(--dg-target);
    color: var(--dg-text-muted);
    font-size: var(--dg-text-caption);
    text-decoration: none;
  }

  a[aria-current='page'] {
    color: var(--dg-accent-text);
    font-weight: 600;
  }

  a:focus-visible {
    outline-offset: -4px;
  }

  svg {
    fill: none;
    stroke: currentColor;
    stroke-width: 2;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
</style>
