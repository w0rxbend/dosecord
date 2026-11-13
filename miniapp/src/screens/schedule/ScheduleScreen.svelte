<script lang="ts">
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import ScheduleBuilder from './ScheduleBuilder.svelte';

  let { medicationId }: { medicationId: string } = $props();

  const { api } = useApp();
  const loaded = new Resource(async () => {
    const [options, medication] = await Promise.all([api.scheduleOptions(), api.medication(medicationId)]);
    return { options, medication };
  });
</script>

<Screen
  title={loaded.data ? en.schedule.title(loaded.data.medication.name) : en.schedule.heading}
  subtitle={loaded.data ? en.schedule.heading : undefined}
>
  <AsyncView resource={loaded} loadingLabel={en.schedule.loading} skeletonRows={5}>
    {#snippet children({ options, medication })}
      <ScheduleBuilder {options} {medication} />
    {/snippet}
  </AsyncView>
</Screen>
