<script lang="ts">
  import { en } from '../i18n/en';

  let { kind }: { kind: 'outside' | 'oldClient' } = $props();
</script>

<!-- A gate screen replaces the whole application: one heading, no API data (MA-GATE-1). -->
<main>
  <h1>{en.gate[kind].title}</h1>
  <p>{en.gate[kind].body}</p>
</main>

<style>
  main {
    display: grid;
    gap: var(--dg-space-3);
    max-inline-size: var(--dg-content-max);
    margin-inline: auto;
    padding: var(--dg-space-7) var(--dg-space-4);
  }

  h1 {
    font-size: var(--dg-text-screen);
    line-height: 1.2;
  }
</style>
