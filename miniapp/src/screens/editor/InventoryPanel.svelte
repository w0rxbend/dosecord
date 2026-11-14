<script lang="ts">
  import { toApiError } from '../../api/client';
  import type { InventoryView } from '../../api/types';
  import { useApp } from '../../app/context';
  import { parseDecimal, isPositive } from '../../forms/decimal';
  import { t as en, errorText } from '../../i18n/index.svelte';
  import Field from '../../ui/Field.svelte';

  // The count of one medication (ROADMAP G9.10, PRODUCT_SPEC R-REG-11 to R-REG-16): what the user
  // enters, the server's sentence for how far it reaches, and a refill. Nothing here advises.
  interface Props {
    medicationId: string;
    current: InventoryView | null;
  }

  let { medicationId, current }: Props = $props();
  const { api, tg, toasts } = useApp();
  const UNITS = { integerDigits: 6, fractionDigits: 3 };

  // The panel starts from the count as the screen loaded it, then keeps its own (as the builder does).
  const loaded = (): InventoryView | null => current;
  let view = $state<InventoryView | null>(loaded());
  let onHand = $state(loaded()?.onHand ?? '');
  let perIntake = $state(loaded()?.perIntake ?? '1');
  let thresholdDays = $state(String(loaded()?.thresholdDays ?? 7));
  let refillQuantity = $state(loaded()?.lastRefill ?? '');
  let busy = $state(false);
  let problem = $state<string | null>(null);

  const parsedOnHand = $derived(parseDecimal(onHand, UNITS));
  const parsedPerIntake = $derived(parseDecimal(perIntake, UNITS));
  const parsedDays = $derived(/^\d{1,3}$/.test(thresholdDays.trim()) ? Number(thresholdDays) : null);
  const valid = $derived(
    parsedOnHand !== null &&
      parsedPerIntake !== null &&
      isPositive(parsedPerIntake) &&
      parsedDays !== null &&
      parsedDays >= 1 &&
      parsedDays <= 365,
  );
  const parsedRefill = $derived(parseDecimal(refillQuantity, UNITS));

  async function run(action: () => Promise<InventoryView>, done: string): Promise<void> {
    if (busy) return;
    busy = true;
    problem = null;
    try {
      view = await action();
      onHand = view.onHand;
      tg.haptic('success');
      toasts.show(done);
    } catch (cause) {
      problem = errorText(toApiError(cause).code);
      tg.haptic('error');
    } finally {
      busy = false;
    }
  }

  function save(): void {
    if (!valid || parsedOnHand === null || parsedPerIntake === null || parsedDays === null) return;
    const input = { onHand: parsedOnHand, perIntake: parsedPerIntake, thresholdDays: parsedDays };
    void run(() => api.saveInventory(medicationId, input, view?.version ?? null), en.inventory.saved);
  }

  function refill(): void {
    if (parsedRefill === null || !isPositive(parsedRefill)) return;
    const quantity = parsedRefill;
    void run(() => api.refill(medicationId, quantity), en.inventory.refilled);
  }
</script>

<section class="section" aria-labelledby="inv-title">
  <h2 class="section-title" id="inv-title">{en.inventory.title}</h2>
  <div class="section-body pad stack">
    <p>{en.inventory.intro}</p>
    {#if view}
      <p class="numeric" aria-live="polite">{view.summary}</p>
      {#if view.belowThreshold}<p>{en.inventory.below}</p>{/if}
      {#if view.countMayBeOff}<p>{en.inventory.countMayBeOff}</p>{/if}
    {/if}
    <Field id="inv-onHand" label={en.inventory.onHand} required>
      {#snippet children(attributes)}
        <input {...attributes} class="input numeric" inputmode="decimal" bind:value={onHand} />
      {/snippet}
    </Field>
    <Field id="inv-perIntake" label={en.inventory.perIntake} required>
      {#snippet children(attributes)}
        <input {...attributes} class="input numeric" inputmode="decimal" bind:value={perIntake} />
      {/snippet}
    </Field>
    <Field id="inv-thresholdDays" label={en.inventory.thresholdDays} required>
      {#snippet children(attributes)}
        <input {...attributes} class="input numeric" inputmode="numeric" bind:value={thresholdDays} />
      {/snippet}
    </Field>
    <button type="button" class="btn btn-tinted" disabled={!valid || busy} onclick={save}>{en.inventory.save}</button>
    {#if view}
      <Field id="inv-refill" label={en.inventory.refillQuantity}>
        {#snippet children(attributes)}
          <input {...attributes} class="input numeric" inputmode="decimal" bind:value={refillQuantity} />
        {/snippet}
      </Field>
      <button type="button" class="btn btn-tinted" disabled={parsedRefill === null || busy} onclick={refill}>
        {en.inventory.refill}
      </button>
    {/if}
    {#if problem}<p class="error" role="alert">{problem}</p>{/if}
  </div>
</section>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
  }

  .error {
    color: var(--dg-danger);
    font-weight: 600;
  }
</style>
