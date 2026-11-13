<script lang="ts">
  import { en } from '../i18n/en';
  import type { GateKind } from './gate';

  interface Props {
    kind: GateKind;
    /** The one action of the screen: Close, or Try again for the offline gate. Absent outside Telegram. */
    action?: { label: string; run: () => void };
  }

  let { kind, action }: Props = $props();
</script>

<!-- A gate screen replaces the whole application: one heading, at most one action, no API
     data (MINI_APP 3.5, MA-GATE-1). -->
<main>
  <h1>{en.gate[kind].title}</h1>
  <p>{en.gate[kind].body}</p>
  {#if action}
    <button type="button" class="btn btn-tinted" onclick={action.run}>{action.label}</button>
  {/if}
</main>

<style>
  main {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
    max-inline-size: var(--dg-content-max);
    margin-inline: auto;
    padding: var(--dg-space-7) var(--dg-space-4);
  }

  h1 {
    font-size: var(--dg-text-screen);
    line-height: 1.2;
  }
</style>
