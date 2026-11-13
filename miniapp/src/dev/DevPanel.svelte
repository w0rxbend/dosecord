<script lang="ts">
  import type { MockBackend, MockScenario } from '../mock/backend';
  import type { FakeTelegramPort } from '../platform/fake';

  let { tg, api }: { tg: FakeTelegramPort; api: MockBackend } = $props();

  // A deliberately weak theme, to see the contrast guard work (MINI_APP 14.1).
  const LOW_CONTRAST = {
    bg_color: '#aaaaaa',
    secondary_bg_color: '#a0a0a0',
    section_bg_color: '#aaaaaa',
    text_color: '#999999',
    link_color: '#9a9aa5',
    button_color: '#eeeeee',
    button_text_color: '#ffffff',
  };
  const THEMES = {
    light: () => tg.setTheme('light'),
    dark: () => tg.setTheme('dark'),
    'low contrast': () => tg.setTheme('light', LOW_CONTRAST),
  };
  const SCENARIOS: readonly MockScenario[] = ['typical', 'empty', 'error'];

  const readChrome = () => ({ main: { ...tg.mainButton.state }, backVisible: tg.backButton.visible });
  let chrome = $state(readChrome());
  const main = $derived(chrome.main);
  const backVisible = $derived(chrome.backVisible);

  $effect(() => tg.onChromeChanged(() => (chrome = readChrome())));
  // Telegram draws its bottom button outside the page; here it overlays it, so make room.
  $effect(() => {
    document.body.style.paddingBlockEnd = main.visible ? '80px' : '';
  });

  function setScenario(scenario: MockScenario): void {
    api.scenario = scenario;
    api.reset();
    tg.activate();
  }
</script>

<!-- Stands in for Telegram's own chrome: the back button, the bottom button, and the controls
     a developer needs. English only; it is not part of the product. -->
<aside class="panel" aria-label="Development controls">
  <button type="button" disabled={!backVisible} onclick={() => tg.backButton.press()}>Back</button>
  {#each Object.entries(THEMES) as [name, apply] (name)}
    <button type="button" onclick={apply}>{name}</button>
  {/each}
  {#each SCENARIOS as scenario (scenario)}
    <button type="button" onclick={() => setScenario(scenario)}>{scenario}</button>
  {/each}
  <button type="button" onclick={() => { api.resolveInChat('occ-3'); }}>answer in chat</button>
</aside>

{#if main.visible}
  <div class="bottom">
    <button type="button" class="btn btn-filled" disabled={!main.enabled || main.inProgress} onclick={() => tg.mainButton.press()}>
      {main.inProgress ? `${main.text}...` : main.text}
    </button>
  </div>
{/if}

<style>
  .panel {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-1);
    padding: var(--dg-space-1) var(--dg-space-4);
    border-block-end: 1px dashed var(--dg-separator);
    font-size: var(--dg-text-caption);
  }

  .bottom {
    position: fixed;
    inset-inline: 0;
    inset-block-end: 0;
    display: grid;
    padding: var(--dg-space-2) var(--dg-space-4);
    background: var(--dg-bottom-bar);
    border-block-start: 1px solid var(--dg-separator);
  }

  .bottom .btn:disabled {
    background: var(--dg-separator);
  }
</style>
