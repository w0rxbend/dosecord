<script lang="ts">
  import { OFF, type MealKind } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import ImportedMark from '../../ui/ImportedMark.svelte';
  import type { DayTracking } from './tracking';

  interface Props {
    food: Exclude<DayTracking['food'], typeof OFF>;
    isToday: boolean;
    onLogMeal: (mealType: MealKind) => void;
  }

  let { food, isToday, onLogMeal }: Props = $props();
  const { router } = useApp();

  /** A main meal is offered until it is logged; a snack always is. A meal needs only its type and time (S-08). */
  const MAIN: readonly MealKind[] = ['breakfast', 'lunch', 'dinner'];
  const offered = $derived<MealKind[]>([...MAIN.filter((meal) => !food.meals.some((logged) => logged.mealType === meal)), 'snack']);
</script>

<section class="section" aria-labelledby="day-food">
  <h2 class="section-title" id="day-food">{en.food.title}</h2>
  <div class="section-body pad card">
    {#if isToday && food.day}
      <p class="figure">{food.day.text}</p>
    {:else if food.meals.length === 0}
      <p class="muted">{en.day.noFood}</p>
    {/if}
    {#if food.meals.length > 0}
      <ul class="entries">
        {#each food.meals as meal (meal.id)}
          <li><span class="time numeric">{meal.time}</span> {meal.text}{#if meal.imported}<ImportedMark />{/if}</li>
        {/each}
      </ul>
    {/if}
    {#if isToday}
      <div class="btn-row" role="group" aria-label={en.today.logMealGroup}>
        {#each offered as meal (meal)}
          <button type="button" class="btn btn-tinted" aria-label={en.today.logMeal(en.food.kinds[meal])} onclick={() => onLogMeal(meal)}>
            {en.food.kinds[meal]}
          </button>
        {/each}
      </div>
    {/if}
    <a class="link" href={router.hrefFor('/log/food')} onclick={(event) => { event.preventDefault(); void router.navigate('/log/food'); }}>
      {en.today.openFood}
    </a>
  </div>
</section>

<style>
  .card {
    display: grid;
    gap: var(--dg-space-3);
  }

  .figure {
    font-weight: 600;
  }

  .entries {
    display: grid;
    gap: var(--dg-space-2);
  }

  .time {
    color: var(--dg-text-muted);
  }
</style>
