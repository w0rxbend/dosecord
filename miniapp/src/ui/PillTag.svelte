<script lang="ts">
  let { colour, shape }: { colour: string; shape: string } = $props();
</script>

<!-- The colour and shape a person chose to recognise a medication. Always shown next to its
     name, with a separator outline so white and yellow stay visible (MA-EDIT-4). -->
<svg class="pill" data-colour={colour} viewBox="0 0 24 24" width="24" height="24" aria-hidden="true">
  {#if shape === 'oval'}
    <ellipse cx="12" cy="12" rx="10" ry="6" />
  {:else if shape === 'capsule'}
    <rect x="2" y="7" width="20" height="10" rx="5" />
  {:else if shape === 'oblong'}
    <rect x="2" y="7" width="20" height="10" rx="2" />
  {:else}
    <circle cx="12" cy="12" r="8" />
  {/if}
</svg>

<style>
  .pill {
    flex: none;
    fill: var(--dg-surface-alt);
    stroke: var(--dg-separator);
    stroke-width: 1;
  }

  .pill[data-colour='white'] { fill: var(--dg-pill-white); }
  .pill[data-colour='yellow'] { fill: var(--dg-pill-yellow); }
  .pill[data-colour='orange'] { fill: var(--dg-pill-orange); }
  .pill[data-colour='pink'] { fill: var(--dg-pill-pink); }
  .pill[data-colour='red'] { fill: var(--dg-pill-red); }
  .pill[data-colour='brown'] { fill: var(--dg-pill-brown); }
  .pill[data-colour='green'] { fill: var(--dg-pill-green); }
  .pill[data-colour='blue'] { fill: var(--dg-pill-blue); }
  .pill[data-colour='purple'] { fill: var(--dg-pill-purple); }
  .pill[data-colour='grey'] { fill: var(--dg-pill-grey); }
</style>
