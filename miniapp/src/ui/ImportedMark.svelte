<script lang="ts">
  import { t as en } from '../i18n/index.svelte';
</script>

<!-- An entry an import brought in says so on every surface (ROADMAP G10.5, PRODUCT_SPEC R-IMX-10). -->
<span class="mark">{en.imports.mark}</span>

<style>
  .mark {
    flex: none;
    padding-inline: var(--dg-space-2);
    border: 1px solid var(--dg-separator);
    border-radius: var(--dg-space-2);
    color: var(--dg-text-muted);
    font-size: 0.8125rem;
  }
</style>
