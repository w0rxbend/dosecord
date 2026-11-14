<script lang="ts">
  import { newIdempotencyKey, toApiError } from '../../api/client';
  import type { BlobAsset, MoodRecorded, MoodScale } from '../../api/types';
  import { useApp } from '../../app/context';
  import { useMainButton } from '../../app/main-button.svelte';
  import { NO_ENTRY, stateIdOf } from '../../charts/blob';
  import MoodBlob from '../../charts/MoodBlob.svelte';
  import { t as en, errorText } from '../../i18n/index.svelte';

  interface Props {
    asset: BlobAsset;
    scale: MoodScale;
    onSaved: (recorded: MoodRecorded) => void;
  }

  let { asset, scale, onSaved }: Props = $props();
  const { api, tg } = useApp();

  const first = $derived(scale.valence[0]?.step ?? 1);
  const last = $derived(scale.valence.at(-1)?.step ?? 5);
  // The slider starts in the middle, untouched; an untouched default is never saved (MA-MOOD-1).
  let valence = $state<number | null>(null);
  let position = $state(3);
  let energy = $state<number | undefined>(undefined);
  let saving = $state(false);
  let failure = $state<string | undefined>(undefined);
  // The key is made once per check-in, so a retry of the same save repeats it (MA-CONC-1).
  const key = newIdempotencyKey();

  const valenceLabel = $derived(valence === null ? en.checkIn.untouched : (scale.valence.find((stop) => stop.step === valence)?.label ?? ''));
  const energyLabel = $derived(energy === undefined ? undefined : scale.energy.find((stop) => stop.step === energy)?.label);
  const blobName = $derived(valence === null ? en.checkIn.untouched : en.checkIn.blobName(valenceLabel, energyLabel));

  function setValence(step: number): void {
    const next = Math.max(first, Math.min(last, step));
    if (next !== valence) tg.haptic('selection');
    valence = next;
    position = next;
  }

  useMainButton(tg, {
    text: en.checkIn.save,
    enabled: () => valence !== null,
    busy: () => saving,
    onPress: () => void save(),
  });

  async function save(): Promise<void> {
    if (valence === null || saving) return;
    saving = true;
    failure = undefined;
    try {
      const recorded = await api.checkIn({ valence, energy }, key);
      tg.haptic('success');
      onSaved(recorded);
    } catch (cause) {
      failure = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      saving = false;
    }
  }
</script>

<section class="section section-body pad form" aria-label={en.checkIn.valence}>
  {#if failure}<p class="error" role="alert">{failure}</p>{/if}
  <!-- The blob and the same words under it: shape and colour never carry the value alone (MA-MOOD-3). -->
  <MoodBlob {asset} stateId={valence === null ? NO_ENTRY : stateIdOf(valence, energy)} label={blobName} size={180} breathe={valence !== null} />
  <p class="label" aria-hidden="true">{blobName}</p>

  <!-- A native range with one stop per level, and two stepper buttons instead of dragging (MA-MOOD-2). -->
  <label class="visually-hidden" for="mood-valence">{en.checkIn.valence}</label>
  <div class="scale-ends muted" aria-hidden="true">
    <span>{scale.valence[0]?.label}</span><span>{scale.valence.at(-1)?.label}</span>
  </div>
  <input
    id="mood-valence"
    type="range"
    min={first}
    max={last}
    step="1"
    bind:value={position}
    aria-valuetext={valenceLabel}
    disabled={saving}
    oninput={() => setValence(Number(position))}
  />
  <div class="btn-row steppers">
    <button type="button" class="btn btn-tinted" aria-label={en.checkIn.less} disabled={saving} onclick={() => setValence((valence ?? 3) - 1)}>−</button>
    <button type="button" class="btn btn-tinted" aria-label={en.checkIn.more} disabled={saving} onclick={() => setValence((valence ?? 3) + 1)}>+</button>
  </div>
</section>

<fieldset class="section section-body pad">
  <legend class="legend">{en.checkIn.energy} <span class="muted">({en.common.optional})</span></legend>
  <div class="chips">
    <label class="chip">
      <input type="radio" name="mood-energy" value={undefined} bind:group={energy} disabled={saving} onchange={() => tg.haptic('selection')} />
      <span>{en.checkIn.noEnergy}</span>
    </label>
    {#each scale.energy as stop (stop.step)}
      <label class="chip">
        <input type="radio" name="mood-energy" value={stop.step} bind:group={energy} disabled={saving} onchange={() => tg.haptic('selection')} />
        <span>{stop.label}</span>
      </label>
    {/each}
  </div>
</fieldset>

<style>
  .form {
    display: grid;
    justify-items: center;
    gap: var(--dg-space-3);
  }

  .label {
    font-size: var(--dg-text-title);
    font-weight: 600;
    text-align: center;
  }

  input[type='range'] {
    inline-size: 100%;
    min-block-size: var(--dg-target);
    accent-color: var(--dg-accent);
  }

  .scale-ends {
    display: flex;
    justify-content: space-between;
    inline-size: 100%;
  }

  .steppers .btn {
    min-inline-size: 64px;
    font-size: var(--dg-text-title);
  }

  .legend {
    font-weight: 600;
    margin-block-end: var(--dg-space-2);
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
