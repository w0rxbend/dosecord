<script lang="ts">
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import MedEditor from './MedEditor.svelte';

  /** Absent for a new medication. */
  let { id }: { id?: string } = $props();

  const { api, router } = useApp();
  const loaded = new Resource(async () => {
    const [options, medication, schedules] = await Promise.all([
      api.medicationOptions(),
      id ? api.medication(id) : null,
      id ? api.schedules(id) : [],
    ]);
    // The times live on the schedule that has not ended (ADR-020 decision 9: at most one).
    return { options, medication, schedule: schedules.find((schedule) => schedule.status !== 'ended') ?? null };
  });

  function open(event: MouseEvent, path: string): void {
    event.preventDefault();
    void router.navigate(path);
  }
</script>

<Screen title={id ? en.editor.titleEdit : en.editor.titleNew}>
  <AsyncView resource={loaded} loadingLabel={en.editor.loading} skeletonRows={5}>
    {#snippet children({ options, medication, schedule })}
      <MedEditor {options} {medication}>
        {#snippet after()}
          {#if medication}
            {@const path = schedule ? `/schedules/${schedule.id}` : `/meds/${medication.id}/schedules/new`}
            <section class="section" aria-labelledby="med-times">
              <h2 class="section-title" id="med-times">{en.editor.times}</h2>
              <div class="section-body pad times">
                {#if schedule}<p class="numeric">{schedule.summary}</p>{/if}
                <a class="btn btn-tinted" href={router.hrefFor(path)} onclick={(event) => open(event, path)}>
                  {schedule ? en.editor.changeTimes : en.editor.addTimes}
                </a>
              </div>
            </section>
          {/if}
        {/snippet}
      </MedEditor>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .times {
    display: grid;
    gap: var(--dg-space-2);
  }
</style>
