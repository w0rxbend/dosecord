<script lang="ts">
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import ScheduleBuilder from './ScheduleBuilder.svelte';

  /** Changes an existing schedule (route sched_<id>, MINI_APP 3.4); the builder saves a new revision. */
  let { id }: { id: string } = $props();

  const { api } = useApp();
  const loaded = new Resource(async () => {
    const [options, schedule] = await Promise.all([api.scheduleOptions(), api.schedule(id)]);
    const medication = await api.medication(schedule.medicationId);
    return { options, schedule, medication };
  });
</script>

<Screen
  title={loaded.data ? en.schedule.title(loaded.data.medication.name) : en.schedule.heading}
  subtitle={loaded.data ? en.schedule.heading : undefined}
>
  <AsyncView resource={loaded} loadingLabel={en.schedule.loading} skeletonRows={5}>
    {#snippet children({ options, schedule, medication })}
      <ScheduleBuilder {options} {medication} {schedule} />
    {/snippet}
  </AsyncView>
</Screen>
