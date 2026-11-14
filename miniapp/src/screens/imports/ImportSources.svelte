<script lang="ts">
  import type { ImportTemplateKind } from '../../api/types';
  import { t as en } from '../../i18n/index.svelte';
  import type { HealthType } from '../../import/health';

  interface Props {
    /** MA-REP-6: until spike MA-SPK-6, device exports are offered on Telegram Desktop only. */
    phone: boolean;
    onTemplate: (kind: ImportTemplateKind) => void;
    onCsv: (file: File) => void;
    onHealth: (file: File, selected: readonly HealthType[]) => void;
  }

  let { phone, onTemplate, onCsv, onHealth }: Props = $props();

  const TEMPLATES: readonly ImportTemplateKind[] = ['water', 'mood', 'sleep', 'meals'];
  let water = $state(true);
  let sleep = $state(true);

  function picked(event: Event): File | undefined {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    return file;
  }

  function pickCsv(event: Event): void {
    const file = picked(event);
    if (file) onCsv(file);
  }

  function pickHealth(event: Event): void {
    const file = picked(event);
    if (file) onHealth(file, [...(water ? ['water' as const] : []), ...(sleep ? ['sleep' as const] : [])]);
  }
</script>

<section class="section" aria-labelledby="csv-title">
  <h2 class="section-title" id="csv-title">{en.imports.csvTitle}</h2>
  <div class="section-body pad stack">
    <p>{en.imports.csvText}</p>
    <p class="muted">{en.imports.templates}</p>
    <div class="buttons">
      {#each TEMPLATES as kind (kind)}
        <button class="btn btn-tinted" type="button" onclick={() => onTemplate(kind)}>{en.imports.template[kind]}</button>
      {/each}
    </div>
    <label class="btn btn-filled file"
      >{en.imports.chooseCsv}<input type="file" accept=".csv,text/csv" onchange={pickCsv} /></label
    >
  </div>
</section>
<section class="section" aria-labelledby="health-title">
  <h2 class="section-title" id="health-title">{en.imports.healthTitle}</h2>
  <div class="section-body pad stack">
    {#if phone}
      <p>{en.imports.healthPhone}</p>
    {:else}
      <p>{en.imports.healthText}</p>
      <label><input type="checkbox" bind:checked={water} /> {en.imports.healthWater}</label>
      <label><input type="checkbox" bind:checked={sleep} /> {en.imports.healthSleep}</label>
      <p class="muted">{en.imports.healthWeightLater}</p>
      <label class="btn btn-filled file"
        >{en.imports.chooseHealth}<input type="file" accept=".zip,.xml" onchange={pickHealth} /></label
      >
    {/if}
  </div>
</section>

<style>
  .stack {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
  }

  .buttons {
    display: flex;
    flex-wrap: wrap;
    gap: var(--dg-space-2);
  }

  .file {
    position: relative;
    overflow: hidden;
  }

  .file input {
    position: absolute;
    inset: 0;
    opacity: 0;
    cursor: pointer;
  }
</style>
