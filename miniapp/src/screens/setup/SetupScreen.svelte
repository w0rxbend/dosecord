<script lang="ts">
  import { useApp } from '../../app/context';
  import { t as en } from '../../i18n/index.svelte';
  import { Resource } from '../../state/resource.svelte';
  import AsyncView from '../../ui/AsyncView.svelte';
  import Illustration from '../../ui/Illustration.svelte';
  import Screen from '../../ui/Screen.svelte';
  import ConsentList from './ConsentList.svelte';
  import SetupStep from './SetupStep.svelte';
  import { confirmZone, isZoneConfirmed, readSetup, stepsDone, type SetupState } from './setup';

  const { api, tg, router } = useApp();
  const setup = new Resource<SetupState>(() => readSetup(api));
  // A consent given in the chat, or a step finished on another screen, shows when the person returns.
  $effect(() => tg.onActivated(() => void setup.load()));

  const deviceZone = Intl.DateTimeFormat().resolvedOptions().timeZone || undefined;
  let zoneVersion = $state(0);

  function done(data: SetupState) {
    void zoneVersion;
    return stepsDone(data, isZoneConfirmed(data.account.timeZone), deviceZone);
  }

  function go(path: string): void {
    void router.navigate(path);
  }

  function keepZone(zone: string): void {
    tg.haptic('selection');
    confirmZone(zone);
    zoneVersion += 1;
  }
</script>

<!-- Set-up for a person who opens the app first (supervisor decision, 2026-11-14): what is on and
     where consent is given, the time zone, the day profile and a first medication or water goal.
     Each step is done when the server's records say so; consent is given in the chat only
     (MA-ROLE-2). -->
<Screen title={en.setup.title}>
  <AsyncView resource={setup} loadingLabel={en.setup.loading} skeletonRows={5}>
    {#snippet children(data)}
      {@const steps = done(data)}
      {@const count = Object.values(steps).filter(Boolean).length}
      <div class="section section-body pad hero">
        <Illustration kind="all" />
        <p>{en.setup.intro}</p>
        <div class="progress">
          <!-- One cell per step, filled when done; the words beside it carry the count. -->
          <span class="cells" aria-hidden="true">
            {#each [steps.consent, steps.zone, steps.day, steps.first] as filled, index (index)}
              <span class="cell" class:filled></span>
            {/each}
          </span>
          <p class="numeric" aria-live="polite">{en.setup.progress(count, 4)}</p>
        </div>
      </div>

      <ol class="steps">
        <SetupStep number={1} title={en.setup.consentTitle} done={steps.consent}>
          <ConsentList consents={data.consents} />
        </SetupStep>

        <SetupStep number={2} title={en.setup.zoneTitle} done={steps.zone}>
          <p>{en.setup.zoneText(data.account.timeZone)}</p>
          {#if deviceZone && deviceZone !== data.account.timeZone}
            <p class="muted">{en.setup.zoneDevice(deviceZone)}</p>
          {/if}
          <div class="btn-row">
            {#if !steps.zone}
              <button type="button" class="btn btn-tinted" onclick={() => keepZone(data.account.timeZone)}>{en.setup.zoneKeep}</button>
            {/if}
            <button type="button" class="btn btn-tinted" onclick={() => go('/settings/tz')}>{en.setup.zoneChange}</button>
          </div>
        </SetupStep>

        <SetupStep number={3} title={en.setup.dayTitle} done={steps.day}>
          <p>{en.setup.dayText}</p>
          <button type="button" class="btn btn-tinted" onclick={() => go('/settings/day')}>
            {steps.day ? en.setup.dayEdit : en.setup.daySet}
          </button>
        </SetupStep>

        <SetupStep number={4} title={en.setup.firstTitle} done={steps.first}>
          {#if data.medications === undefined && data.water === undefined}
            <p>{en.setup.firstOff}</p>
          {:else}
            <p>{en.setup.firstText}</p>
            {#if data.medications && data.medications.length > 0}
              <p class="muted">{en.setup.medicationsSet(data.medications.length)}</p>
            {/if}
            {#if data.water?.goalMl !== undefined}
              <p class="muted">{en.setup.goalSet(data.water.goalMl)}</p>
            {/if}
            <div class="btn-row">
              {#if data.medications !== undefined}
                <button type="button" class="btn btn-tinted" onclick={() => go('/meds/new')}>{en.setup.firstMedication}</button>
              {/if}
              {#if data.water !== undefined}
                <button type="button" class="btn btn-tinted" onclick={() => go('/log/water/settings')}>{en.setup.firstGoal}</button>
              {/if}
            </div>
          {/if}
        </SetupStep>
      </ol>

      <div class="section">
        <button type="button" class="btn btn-filled wide" onclick={() => go('/')}>{en.setup.finish}</button>
      </div>
    {/snippet}
  </AsyncView>
</Screen>

<style>
  .hero {
    display: grid;
    gap: var(--dg-space-3);
    justify-items: start;
    padding-block: var(--dg-space-5);
  }

  .progress {
    display: flex;
    align-items: center;
    gap: var(--dg-space-3);
    font-weight: 600;
  }

  .cells {
    display: flex;
    gap: var(--dg-space-1);
  }

  .cell {
    inline-size: 20px;
    block-size: 8px;
    border-radius: 4px;
    background: var(--dg-separator);
  }

  .cell.filled {
    background: var(--dg-recorded);
  }

  .steps {
    display: grid;
    gap: var(--dg-space-3);
    margin: 0 0 var(--dg-space-5);
    padding: 0;
    list-style: none;
  }

  .wide {
    inline-size: 100%;
  }
</style>
