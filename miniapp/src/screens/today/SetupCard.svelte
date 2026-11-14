<script lang="ts">
  import type { Reminders } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import Illustration from '../../ui/Illustration.svelte';
  import { daySet } from '../setup/setup';

  const { api, router } = useApp();
  // Read after Today itself has answered, so the launch keeps its two requests (MA-LAUNCH-6).
  const reminders = new Resource<Reminders>(() => api.reminders());
</script>

<!-- Shown on Today while the day profile was never saved: the person opened the app first and has
     not set it up. It leads to set-up and disappears once the day is saved. A failed read shows
     nothing: the card is an offer, not content. -->
{#if reminders.data && !daySet(reminders.data.day)}
  <section class="section section-body pad card" aria-labelledby="setup-card">
    <Illustration kind="all" />
    <h2 id="setup-card" class="title">{en.setup.card}</h2>
    <p>{en.setup.cardText}</p>
    <a
      class="btn btn-filled"
      href={router.hrefFor('/setup')}
      onclick={(event) => {
        event.preventDefault();
        void router.navigate('/setup');
      }}>{en.setup.cardAction}</a
    >
  </section>
{/if}

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .title {
    font-size: var(--dg-text-title);
    font-weight: 600;
  }
</style>
