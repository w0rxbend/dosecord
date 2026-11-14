<script lang="ts" module>
  export interface ControlAttributes {
    id: string;
    'aria-describedby': string | undefined;
    'aria-invalid': 'true' | undefined;
    'aria-required': 'true' | undefined;
  }
</script>

<script lang="ts">
  import type { Snippet } from 'svelte';
  import { t as en } from '../i18n/index.svelte';

  interface Props {
    id: string;
    label: string;
    required?: boolean;
    optional?: boolean;
    help?: string;
    error?: string;
    children: Snippet<[ControlAttributes]>;
  }

  let { id, label, required = false, optional = false, help, error, children }: Props = $props();

  const describedBy = $derived(
    [help ? `${id}-help` : null, error ? `${id}-error` : null].filter(Boolean).join(' ') || undefined,
  );
</script>

<!-- Label above the control, help and error below it and tied to it (MA-EDIT-1, MA-EDIT-2). -->
<div class="field">
  <label for={id}>
    {label}
    {#if required}
      <span aria-hidden="true">*</span> <span class="visually-hidden">{en.common.required}</span>
    {:else if optional}
      <span class="muted">({en.common.optional})</span>
    {/if}
  </label>
  {@render children({
    id,
    'aria-describedby': describedBy,
    'aria-invalid': error ? 'true' : undefined,
    'aria-required': required ? 'true' : undefined,
  })}
  {#if help}<p class="muted" id="{id}-help">{help}</p>{/if}
  {#if error}<p class="error" id="{id}-error">{error}</p>{/if}
</div>

<style>
  .field {
    display: grid;
    gap: var(--dg-space-1);
  }

  label {
    font-weight: 600;
  }

  .error {
    color: var(--dg-danger);
    font-size: var(--dg-text-secondary);
    font-weight: 600;
  }
</style>
