package dosegram.core.application

import dosegram.contracts.ChatAdapter
import dosegram.contracts.CommandSpec
import dosegram.core.application.capture.CaptureHandler
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.dose.DoseIntakeHandler
import dosegram.core.application.identity.AccountCreateFlow
import dosegram.core.application.identity.AccountTimezoneFlow
import dosegram.core.application.medication.AddMedicationFlow
import dosegram.core.application.menu.MenuHandler
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatHandler
import dosegram.core.chat.Flow
import dosegram.core.chat.WizardEngine
import dosegram.core.ports.Clock
import dosegram.core.ports.UnitOfWork
import dosegram.core.scheduling.ScheduleLifecycle

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
    compose(uow, adapters, codec, clock)(CommandRegistry.all, menu => menu)

  /** The same composition with the capture surface (`/water`, `/mood`, `/stats`, `/privacy`, quick-capture text)
    * between the dose handler and the menu, and `/help` over [[CommandRegistry.withCapture]]. Dose taps and wizard
    * answers are taken before it, so a typed amount inside a wizard step stays the wizard's.
    */
  def withCapture(uow: UnitOfWork, adapters: Map[String, ChatAdapter], codec: CallbackCodec, clock: Clock)(
      capture: CaptureParts
  ): ChatHandler =
    compose(uow, adapters, codec, clock)(
      CommandRegistry.withCapture,
      menu => new CaptureHandler(codec, clock, capture, inner = menu)
    )

  private def compose(uow: UnitOfWork, adapters: Map[String, ChatAdapter], codec: CallbackCodec, clock: Clock)(
      commands: List[CommandSpec],
      beforeMenu: ChatHandler => ChatHandler
  ): ChatHandler =
    val lifecycle = ScheduleLifecycle(uow, clock)
    val menu = new MenuHandler(codec, clock, lifecycle, inner = FirstFlows.innerHandlers(commands))
    val dose = new DoseIntakeHandler(codec, clock, inner = beforeMenu(menu))
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
