<script lang="ts">
  import type { Occurrence, OccurrenceAction, TodayView } from '../../api/types';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import EmptyState from '../../ui/EmptyState.svelte';
  import AsNeededSheet from './AsNeededSheet.svelte';
  import OccurrenceSheet from './OccurrenceSheet.svelte';
  import TodayDoses from './TodayDoses.svelte';

  interface Props {
    view: TodayView;
    onAction: (occurrence: Occurrence, action: OccurrenceAction) => void;
  }

  let { view, onAction }: Props = $props();
  const { router } = useApp();
  let openId = $state<string | undefined>(undefined);
  const opened = $derived(view.occurrences.find((occurrence) => occurrence.id === openId));
  const namesWithDoses = $derived(view.occurrences.map((occurrence) => occurrence.medicationName));
</script>

{#if view.occurrences.length === 0}
  <div class="section">
    <EmptyState message={en.today.empty} actionLabel={en.today.emptyAction} onAction={() => router.navigate('/meds/new')} art="medication" />
  </div>
{:else}
  {#if view.answerInChat}<p class="note">{en.today.answerInChat}</p>{/if}
  <TodayDoses
    occurrences={view.occurrences}
    readOnly={view.readOnly}
    planned={!view.answerInChat}
    {onAction}
    onOpen={(occurrence) => (openId = occurrence.id)}
  />
{/if}
<AsNeededSheet {namesWithDoses} />
{#if opened}
  <OccurrenceSheet occurrence={opened} onAction={view.readOnly ? undefined : onAction} onClose={() => (openId = undefined)} />
{/if}
