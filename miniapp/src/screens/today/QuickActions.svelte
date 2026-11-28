<script lang="ts">
  import type { Occurrence } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';

  interface Props {
    /** The earliest dose due now; absent without one, on a read-only day or without the consent. */
    dose?: Occurrence;
    /** The default vessel: the first of the person's own amounts (supervisor decision, 2026-11-28). */
    vesselMl?: number;
    moodOn: boolean;
    onTake: (occurrence: Occurrence) => Promise<void>;
    onAddWater: (amountMl: number) => void;
  }

  let { dose, vesselMl, moodOn, onTake, onAddWater }: Props = $props();
  const { router } = useApp();
  // The button stays inactive until the server has answered, so a second tap of the same press never
  // answers another dose that has moved into its place (MA-TODAY-2).
  let taking = $state(false);

  async function take(occurrence: Occurrence): Promise<void> {
    if (taking) return;
    taking = true;
    try {
      await onTake(occurrence);
    } finally {
      taking = false;
    }
  }

  function openCheckIn(event: MouseEvent): void {
    event.preventDefault();
    void router.navigate('/log/mood/new');
  }
</script>

<!-- The three daily answers within one tap at the top of Today (MINI_APP 7.1, quick actions). -->
{#if dose || vesselMl !== undefined || moodOn}
  <section class="section" aria-labelledby="quick-title">
    <h2 class="section-title" id="quick-title">{en.quick.title}</h2>
    <div class="section-body pad quick">
      {#if dose}
        <button type="button" class="btn btn-filled numeric" disabled={taking} onclick={() => void take(dose)}>
          {en.quick.taken(dose.medicationName, dose.scheduledTime)}
        </button>
      {/if}
      {#if vesselMl !== undefined}
        <button type="button" class="btn btn-tinted numeric" onclick={() => onAddWater(vesselMl)}>{en.quick.water(vesselMl)}</button>
      {/if}
      {#if moodOn}
        <a class="btn btn-tinted" href={router.hrefFor('/log/mood/new')} onclick={openCheckIn}>{en.quick.mood}</a>
      {/if}
    </div>
  </section>
{/if}

<style>
  .quick {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-2);
  }

  .quick > * {
    flex: 1 1 auto;
  }
</style>
