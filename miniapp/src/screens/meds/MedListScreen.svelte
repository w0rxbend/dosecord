<script lang="ts">
  import type { MedicationStatus, MedicationSummary } from '../../api/types';
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import Screen from '../../ui/Screen.svelte';
  import { countByStatus, filterMedications } from './filter';
  import MedRow from './MedRow.svelte';

  const STATUSES: readonly MedicationStatus[] = ['active', 'paused', 'archived'];

  const { api, tg, router } = useApp();
  const medications = new Resource<readonly MedicationSummary[]>(() => api.medications());

  let query = $state('');
  let status = $state<MedicationStatus>('active');

  const all = $derived(medications.data ?? []);
  const counts = $derived(countByStatus(all));
  const visible = $derived(filterMedications(all, status, query));

  $effect(() => tg.onActivated(() => void medications.load()));

  const editPath = (id: string): string => `/meds/${id}/edit`;
  const addMedication = (): void => void router.navigate('/meds/new');
</script>

<Screen title={en.meds.title}>
  {#snippet actions()}
    <button type="button" class="btn btn-filled" onclick={addMedication}>{en.meds.add}</button>
  {/snippet}

  <AsyncView resource={medications} loadingLabel={en.meds.loading} skeletonRows={4}>
    {#snippet children(list)}
      {#if list.length === 0}
        <EmptyState message={en.meds.empty} actionLabel={en.meds.add} onAction={addMedication} />
      {:else}
        <div class="section tools">
          <label for="med-search">{en.meds.search}</label>
          <input
            id="med-search"
            class="input"
            type="search"
            autocomplete="off"
            spellcheck="false"
            bind:value={query}
          />
          <fieldset>
            <legend class="visually-hidden">{en.meds.filter}</legend>
            <div class="chips">
              {#each STATUSES as option (option)}
                <label class="chip">
                  <input
                    type="radio"
                    name="med-status"
                    value={option}
                    bind:group={status}
                    onchange={() => tg.haptic('selection')}
                  />
                  <span class="numeric">{en.meds.status[option]} {counts[option]}</span>
                </label>
              {/each}
            </div>
          </fieldset>
        </div>

        <!-- The result count is announced without moving focus (MA-REG-1). -->
        <p class="visually-hidden" role="status">{en.meds.count(visible.length)}</p>

        {#if visible.length > 0}
          <ul class="section section-body">
            {#each visible as medication (medication.id)}
              <MedRow
                {medication}
                href={medication.status === 'archived' ? undefined : router.hrefFor(editPath(medication.id))}
                onOpen={() => void router.navigate(editPath(medication.id))}
              />
            {/each}
          </ul>
          {#if status === 'archived'}<p class="note">{en.meds.archivedReadOnly}</p>{/if}
        {:else if query.trim() !== ''}
          <EmptyState message={en.meds.noMatch} actionLabel={en.meds.clearSearch} onAction={() => (query = '')} />
        {:else}
          <EmptyState message={en.meds.noneInFilter} />
        {/if}
      {/if}
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .tools {
    display: grid;
    gap: var(--dg-space-2);
  }

  label[for] {
    font-weight: 600;
  }
</style>
