<script lang="ts">
  import { en } from '../../i18n/en';
  import Field from '../../ui/Field.svelte';

  interface Props {
    busy: boolean;
    onAdd: (amountMl: number) => void;
    min?: number;
    max?: number;
  }

  let { busy, onAdd, min = 1, max = 5000 }: Props = $props();
  let open = $state(false);
  let typed = $state('');
  let error = $state<string | undefined>(undefined);

  function submit(event: SubmitEvent): void {
    event.preventDefault();
    const amount = Number(typed.trim());
    if (!Number.isInteger(amount) || amount < min || amount > max) {
      error = en.water.amountInvalid(min, max);
      return;
    }
    error = undefined;
    typed = '';
    open = false;
    onAdd(amount);
  }
</script>

<!-- "Other amount": a typed whole number of millilitres, checked here and again by the server. -->
<div class="other">
  <button type="button" class="btn btn-tinted" aria-expanded={open} aria-controls="water-other" onclick={() => (open = !open)}>
    {en.water.other}
  </button>
  {#if open}
    <form id="water-other" class="form" onsubmit={submit} novalidate>
      <Field id="water-amount" label={en.water.amount} help={en.water.amountHelp(min, max)} {error}>
        {#snippet children(attributes)}
          <input {...attributes} class="input numeric" inputmode="numeric" autocomplete="off" bind:value={typed} />
        {/snippet}
      </Field>
      <button type="submit" class="btn btn-filled" disabled={busy}>{en.water.addOther}</button>
    </form>
  {/if}
</div>

<style>
  .other {
    grid-column: 1 / -1;
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .form {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
    inline-size: 100%;
  }
</style>
