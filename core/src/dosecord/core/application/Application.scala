package dosecord.core.application

import dosecord.contracts.ChatAdapter
import dosecord.core.application.dose.DoseIntakeHandler
import dosecord.core.application.identity.AccountCreateFlow
import dosecord.core.application.identity.AccountTimezoneFlow
import dosecord.core.application.medication.AddMedicationFlow
import dosecord.core.application.menu.MenuHandler
import dosecord.core.chat.CallbackCodec
import dosecord.core.chat.ChatHandler
import dosecord.core.chat.Flow
import dosecord.core.chat.WizardEngine
import dosecord.core.ports.Clock
import dosecord.core.ports.UnitOfWork
import dosecord.core.scheduling.ScheduleLifecycle

/** The M1.10 handler composition (DESIGN.md sections 3 and 4.6 step 6): the identity gate in front of the
  * [[WizardEngine]] — which owns the account-create, add-medication and account-timezone flows' sessions — in front of
  * the M1.10 [[DoseIntakeHandler]] (the one-tap intake flows and the universal `/taken` `/snooze` `/skip` `/log`
  * commands), in front of the [[MenuHandler]] (main menu, Medications submenu with the pause/resume/archive toggle and
  * the Log dose entry, Today, History, Settings), in front of the M0.12d plain command handlers. `/start` and
  * `ConversationStarted` are deliberately not engine `flowCommands`: the gate checks the stamped principal first, so a
  * second create from an already-linked identity answers with the catalogue error and writes no row — not even a
  * session (R3).
  */
object Application:

  def flows(clock: Clock, lifecycle: ScheduleLifecycle): Map[String, Flow] =
    Map(
      AccountCreateFlow.Id -> AccountCreateFlow.flow(clock),
      AddMedicationFlow.Id -> AddMedicationFlow.flow(clock, lifecycle),
      AccountTimezoneFlow.Id -> AccountTimezoneFlow.flow(clock, lifecycle)
    )

  def handler(uow: UnitOfWork, adapters: Map[String, ChatAdapter], codec: CallbackCodec, clock: Clock): ChatHandler =
    val lifecycle = ScheduleLifecycle(uow, clock)
    val menu = new MenuHandler(codec, clock, lifecycle, inner = FirstFlows.innerHandlers(clock))
    val dose = new DoseIntakeHandler(codec, clock, inner = menu)
    val engine = WizardEngine(
      flows = flows(clock, lifecycle),
      flowCommands = Map.empty,
      codec = codec,
      clock = clock,
      inner = dose,
      uow = uow,
      adapters = adapters
    )
    menu.wireEngine(engine)
    FirstFlows.identityGate(engine)
end Application
