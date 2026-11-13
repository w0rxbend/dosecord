<script lang="ts">
  import type { HydrationDay } from '../../api/types';
  import { en } from '../../i18n/en';

  interface Props {
    hydration: HydrationDay;
    canAdd: boolean;
    onAdd: (amountMl: number) => void;
  }

  let { hydration, canAdd, onAdd }: Props = $props();
</script>

<!-- One tap per preset; the new total shows at once (MINI_APP 7.1 behaviour 5). -->
<section class="section" aria-labelledby="water-title">
  <h2 class="section-title" id="water-title">{en.today.water}</h2>
  <div class="section-body">
    <p class="pad numeric">{en.today.waterTotal(hydration.totalMl, hydration.goalMl)}</p>
    {#if canAdd}
      <div class="pad btn-row">
        {#each hydration.presetsMl as amount (amount)}
          <button type="button" class="btn btn-tinted numeric" onclick={() => onAdd(amount)}>
            {en.today.addWater(amount)}
          </button>
        {/each}
      </div>
    {/if}
  </div>
</section>
