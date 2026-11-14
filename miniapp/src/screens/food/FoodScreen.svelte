<script lang="ts">
  import { ApiError, newIdempotencyKey, toApiError, unlessOff, type FieldError } from '../../api/client';
  import { OFF, type LocalDate, type Meal, type MealKind, type NutritionSummary, type Off } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import OffState from '../../ui/OffState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import MealForm from './MealForm.svelte';
  import { byType, editDraft, MAIN_MEALS, MEAL_KINDS, newDraft, shiftDate, toMealInput, type MealDraft } from './meal';
  import PlannedMealsForm from './PlannedMealsForm.svelte';

  interface Loaded {
    readonly summary: NutritionSummary | Off;
    readonly date: LocalDate;
    readonly meals: readonly Meal[];
  }

  const { api, tg, toasts } = useApp();

  /** The day shown; absent is today, which only the server knows in the account zone. */
  let date = $state<LocalDate | undefined>(undefined);
  let draft = $state<MealDraft | null>(null);
  let busy = $state(false);
  let errors = $state<readonly FieldError[]>([]);

  // The week ending on the day shown carries that day and the weekly line; the day's meals are read beside it.
  // Without a day the server answers the week ending today in the account zone.
  async function read(day: LocalDate | undefined): Promise<Loaded> {
    const summary = await unlessOff(day === undefined ? api.nutritionSummary() : api.nutritionSummary(shiftDate(day, -6), day));
    if (summary === OFF) return { summary, date: day ?? '', meals: [] };
    const shown = day ?? summary.today.date;
    return { summary, date: shown, meals: await api.meals(shown, shown) };
  }

  const food = new Resource<Loaded>(() => read(date));
  $effect(() => tg.onActivated(() => void food.load()));

  function show(day: LocalDate | undefined): void {
    date = day;
    draft = null;
    void food.load(() => read(day));
  }

  function openNew(mealType: MealKind, data: Loaded, today: LocalDate): void {
    errors = [];
    draft = newDraft(mealType, data.date, today, new Date());
  }

  async function save(next: MealDraft): Promise<void> {
    busy = true;
    try {
      await api.addMeal(toMealInput(next), newIdempotencyKey());
      errors = [];
      draft = null;
      tg.haptic('success');
      toasts.show(en.food.saved);
      await food.load();
    } catch (cause) {
      tg.haptic('error');
      if (cause instanceof ApiError && cause.code === 'validation') errors = cause.fields;
      else toasts.show(errorText(toApiError(cause).code));
    } finally {
      busy = false;
    }
  }

  async function remove(meal: Meal): Promise<void> {
    try {
      await api.removeMeal(meal.id);
      toasts.show(en.food.removed);
      await food.load();
    } catch {
      tg.haptic('error');
      toasts.show(en.errors.notSaved);
    }
  }
</script>

<Screen title={en.food.title} subtitle={food.data && food.data.summary !== OFF ? food.data.summary.days.at(-1)?.text : undefined}>
  {#snippet actions()}
    {#if food.data && food.data.summary !== OFF}
      {@const data = food.data}
      {@const isToday = data.summary !== OFF && data.date === data.summary.today.date}
      <button type="button" class="btn" aria-label={en.food.previousDay} onclick={() => show(shiftDate(data.date, -1))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </button>
      <button type="button" class="btn" aria-label={en.food.nextDay} disabled={isToday} onclick={() => show(shiftDate(data.date, 1))}>
        <svg viewBox="0 0 24 24" width="24" height="24" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
      </button>
    {/if}
  {/snippet}
  <AsyncView resource={food} loadingLabel={en.food.loading} skeletonRows={4}>
    {#snippet children(data)}
      {#if data.summary === OFF}
        <div class="section"><OffState domain="nutrition" /></div>
      {:else}
        {@const summary = data.summary}
        {@const today = summary.today.date}
        {@const day = summary.days.at(-1)}
        {@const groups = byType(data.meals)}
        {#if data.date !== today}
          <p class="section"><button type="button" class="btn btn-tinted" onclick={() => show(undefined)}>{en.food.backToToday}</button></p>
        {/if}
        <!-- The four meal groups; a planned main meal without an entry is "Not logged", never missed (MA-FOOD-1). -->
        {#each MEAL_KINDS as kind (kind)}
          <section class="section" aria-labelledby={`food-${kind}`}>
            <h2 class="section-title" id={`food-${kind}`}>{en.food.meals[kind]}</h2>
            <div class="section-body pad group">
              {#each groups[kind] as meal (meal.id)}
                <div class="row">
                  <span class="text numeric">{meal.time} {meal.description ?? ''}</span>
                  <button type="button" class="btn" aria-label={en.food.change(meal.text)} onclick={() => { errors = []; draft = editDraft(meal); }}>›</button>
                  <button type="button" class="btn" aria-label={en.food.remove(meal.text)} onclick={() => remove(meal)}>{en.water.removeShort}</button>
                </div>
              {:else}
                {#if MAIN_MEALS.includes(kind) && day?.notLogged.includes(kind)}<p class="muted">{en.food.notLogged}</p>{/if}
              {/each}
              {#if groups[kind].length === 0}
                <button type="button" class="btn btn-tinted" onclick={() => openNew(kind, data, today)}>{en.food.addOf(en.food.kinds[kind])}</button>
              {/if}
            </div>
          </section>
        {/each}

        <section class="section" aria-labelledby="food-sheet">
          <h2 class="section-title" id="food-sheet">{draft?.replaces ? en.food.edit : en.food.add}</h2>
          <div class="section-body pad">
            {#if draft}
              {#key draft}
                <MealForm {draft} {busy} {errors} onSave={save} onCancel={() => (draft = null)} />
              {/key}
            {:else}
              <button type="button" class="btn btn-filled" onclick={() => openNew('snack', data, today)}>+ {en.food.add}</button>
            {/if}
          </div>
        </section>

        <section class="section" aria-labelledby="food-week">
          <h2 class="section-title" id="food-week">{en.food.week}</h2>
          <p class="section-body pad">{summary.text}</p>
        </section>

        <section class="section" aria-labelledby="food-planned">
          <h2 class="section-title" id="food-planned">{en.food.planned}</h2>
          <div class="section-body pad"><PlannedMealsForm onSaved={() => void food.load()} /></div>
        </section>
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .group {
    display: grid;
    gap: var(--dg-space-2);
    justify-items: start;
  }

  .row {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    inline-size: 100%;
  }

  .text {
    flex: 1;
  }
</style>
