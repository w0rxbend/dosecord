<script lang="ts">
  import { useApp } from '../../app/context';
  import { en } from '../../i18n/en';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Screen from '../../ui/Screen.svelte';
  import MedEditor from './MedEditor.svelte';

  /** Absent for a new medication. */
  let { id }: { id?: string } = $props();

  const { api } = useApp();
  const loaded = new Resource(async () => {
    const [options, medication] = await Promise.all([api.medicationOptions(), id ? api.medication(id) : null]);
    return { options, medication };
  });
</script>

<Screen title={id ? en.editor.titleEdit : en.editor.titleNew}>
  <AsyncView resource={loaded} loadingLabel={en.editor.loading} skeletonRows={5}>
    {#snippet children({ options, medication })}
      <MedEditor {options} {medication} />
    {/snippet}
  </AsyncView>
</Screen>
