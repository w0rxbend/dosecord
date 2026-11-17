<script lang="ts">
  import { ApiError, newIdempotencyKey } from '../../api/client';
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';

  let { checkedIn, onRecorded }: { checkedIn: boolean; onRecorded: () => void } = $props();
  const { api, tg } = useApp();
  let steps = $state<number[]>([]);
  let failed = $state(false);
  let saving = $state(false);
  let savedStep = $state<number | null>(null);
  const key = newIdempotencyKey();

  $effect(() => {
    if (checkedIn || savedStep !== null) return;
    let current = true;
    failed = false;
    void api.moodScale().then(
      (scale) => {
        if (current) steps = scale.valence.map((stop) => stop.step);
      },
      (cause: unknown) => {
        if (!current) return;
        if (!(cause instanceof ApiError)) throw cause;
        failed = true;
      },
    );
    return () => {
      current = false;
    };
  });

  async function save(step: number): Promise<void> {
    if (saving || savedStep !== null) return;
    saving = true;
    failed = false;
    try {
      await api.checkIn({ valence: step }, key);
      savedStep = step;
      tg.haptic('success');
      onRecorded();
    } catch (cause) {
      if (!(cause instanceof ApiError)) throw cause;
      failed = true;
      tg.haptic('error');
    } finally {
      saving = false;
    }
  }
</script>

{#if savedStep !== null}
  <p class="numeric" role="status">{en.moodInline.recorded(savedStep)}</p>
{:else if !checkedIn}
  {#if failed}<p role="alert">{en.errors.notSaved}</p>{/if}
  <div class="btn-row" role="group" aria-label={en.moodInline.label}>
    {#each steps as step (step)}
      <button
        type="button"
        class="btn btn-tinted numeric"
        aria-label={en.moodInline.record(step)}
        disabled={saving}
        onclick={() => void save(step)}
      >
        {step}
      </button>
    {/each}
  </div>
{/if}
