<script lang="ts">
  import type { TrackedDomain } from '../api/types';
  import { en } from '../i18n/en';

  let { domain }: { domain: TrackedDomain } = $props();
</script>

<!-- MINI_APP 8.2 "Consent needed": what is off and how it is turned on. Consent is given in the
     chat only; the app records none of its own (MA-ROLE-2). -->
<div class="section-body pad state" role="status">
  <p>{en.off[domain]}</p>
  <p class="muted">{en.off.howTo[domain]}</p>
</div>

<style>
  .state {
    display: grid;
    gap: var(--dg-space-2);
  }
</style>
