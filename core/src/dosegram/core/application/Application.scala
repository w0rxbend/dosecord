package dosegram.core.application

import dosegram.contracts.ChatAdapter
import dosegram.core.application.capture.CaptureHandler
import dosegram.core.application.capture.CaptureParts
import dosegram.core.application.dose.DoseIntakeHandler
import dosegram.core.application.exports.ExportChat
import dosegram.core.application.exports.ExportMessages
import dosegram.core.application.exports.ExportRequests
import dosegram.core.application.identity.AccountCreateFlow
import dosegram.core.application.identity.AccountTimezoneFlow
import dosegram.core.application.medication.AddMedicationFlow
import dosegram.core.application.menu.MenuHandler
import dosegram.core.application.reminders.ReminderSettings
import dosegram.core.application.reminders.RemindersHandler
import dosegram.core.chat.CallbackCodec
import dosegram.core.chat.ChatHandler
import dosegram.core.chat.Flow
import dosegram.core.chat.WizardEngine
import dosegram.core.ports.Clock
import dosegram.core.ports.IdSource
import dosegram.core.ports.UnitOfWork
import dosegram.core.scheduling.ScheduleLifecycle

/** The handler composition (DESIGN.md sections 3 and 4.6 step 6): the identity gate in front of the [[WizardEngine]] —
  * which owns the account-create, add-medication and account-timezone flows' sessions — in front of the M1.10
  * [[DoseIntakeHandler]] (the one-tap intake flows and the universal `/taken` `/snooze` `/skip` `/log` commands), in
  * front of the [[MenuHandler]] (main menu, Medications submenu with the pause/resume/archive toggle and the Log dose
  * entry, Today, History, Settings), in front of the M0.12d plain command handlers. `/start` and `ConversationStarted`
  * are deliberately not engine `flowCommands`: the gate checks the stamped principal first, so a second create from an
  * already-linked identity answers with the catalogue error and writes no row — not even a session (R3).
  */
object Application:

  def flows(clock: Clock, lifecycle: ScheduleLifecycle): Map[String, Flow] =
    Map(
      AccountCreateFlow.Id -> AccountCreateFlow.flow(clock),
      AddMedicationFlow.Id -> AddMedicationFlow.flow(clock, lifecycle),
      AccountTimezoneFlow.Id -> AccountTimezoneFlow.flow(clock, lifecycle)
    )

  /** The one composition of the product. The capture surface (`/water`, `/mood`, `/stats`, `/privacy`, quick-capture
    * text, and every consent card) sits between the dose handler and the menu, and `/help` renders
    * [[CommandRegistry.withCapture]]. Dose taps and wizard answers are taken before it, so a typed amount inside a
    * wizard step stays the wizard's. There is no composition without the consents: the medication handlers answer a
    * missing consent with a card whose taps the capture handler takes (PRODUCT_SPEC R-IDC-9).
    */
  def handler(uow: UnitOfWork, adapters: Map[String, ChatAdapter], codec: CallbackCodec, clock: Clock)(
      capture: CaptureParts
  ): ChatHandler =
    val lifecycle = ScheduleLifecycle(uow, clock)
    val help = FirstFlows.innerHandlers(CommandRegistry.withCapture)
    val menu = new MenuHandler(codec, clock, lifecycle, inner = help)
    val reminders = new RemindersHandler(codec, clock, Application.reminderSettings(clock), capture, inner = menu)
    val exports = ExportChat(clock, exportRequests(clock, codec), ExportMessages(codec), inner = reminders)
    val dose = new DoseIntakeHandler(codec, clock, inner = new CaptureHandler(codec, clock, capture, inner = exports))
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

  /** The export requests, as the chat and the Mini App API share them (ROADMAP G3.10). */
  def exportRequests(clock: Clock, codec: CallbackCodec): ExportRequests =
    ExportRequests(clock, IdSource.uuidV7(java.security.SecureRandom()), ExportMessages(codec))

  /** The settings use cases of the prompts, as the handler and the prompt loop share them (ADR-023). */
  def reminderSettings(clock: Clock): ReminderSettings =
    ReminderSettings(clock, IdSource.uuidV7(java.security.SecureRandom()))
end Application
