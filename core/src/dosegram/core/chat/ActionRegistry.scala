package dosegram.core.chat

final case class ActionEntry(id: Int, name: String, requiresSession: Boolean)

// Static action registry of DESIGN.md section 4.4 / ADR-006: requiresSession marks wizard-bound steps.
object ActionRegistry:
  val entries: List[ActionEntry] = List(
    ActionEntry(1, "dose.taken", requiresSession = false),
    ActionEntry(2, "dose.snooze", requiresSession = false),
    ActionEntry(3, "dose.skip", requiresSession = false),
    ActionEntry(4, "dose.undo", requiresSession = false),
    ActionEntry(5, "dose.note", requiresSession = false),
    // dose.correct is a two-choice button row (M1.10, M2.2); its value carries the choice
    // (0 = open the prompt, 1 = Now, 2 = At scheduled time, 3 = Cancel).
    ActionEntry(6, "dose.correct", requiresSession = false),
    ActionEntry(7, "dose.keep_missed", requiresSession = false),
    // M1.10: direct mode on the medication id (medication ids never expire, like occurrence ids); the value stays 0.
    ActionEntry(8, "dose.log", requiresSession = false),
    ActionEntry(20, "menu.open", requiresSession = false),
    // M1.9: menu entries that open a flow whose first step is a Form (Add medication).
    ActionEntry(21, "menu.open_form", requiresSession = false),
    ActionEntry(30, "wizard.step", requiresSession = true),
    ActionEntry(31, "wizard.text_step", requiresSession = true),
    ActionEntry(32, "wizard.confirm", requiresSession = true),
    ActionEntry(40, "link.confirm", requiresSession = false),
    // Consent, water, mood and statistics (BOT_UX sections 5.5, 11.2, 11.4, 12.5): direct mode on the account id, or
    // on the entry for an Undo and the energy step; the value carries the stage, the amount, the step or the period.
    ActionEntry(50, "consent.step", requiresSession = false),
    ActionEntry(51, "consent.withdraw", requiresSession = false),
    ActionEntry(60, "water.add", requiresSession = false),
    ActionEntry(61, "water.undo", requiresSession = false),
    ActionEntry(62, "water.goal", requiresSession = false),
    ActionEntry(63, "water.goal_ask", requiresSession = false),
    ActionEntry(64, "water.image", requiresSession = false),
    ActionEntry(70, "mood.checkin", requiresSession = false),
    ActionEntry(71, "mood.energy", requiresSession = false),
    ActionEntry(72, "mood.undo", requiresSession = false),
    ActionEntry(80, "stats.show", requiresSession = false),
    ActionEntry(81, "stats.image", requiresSession = false),
    ActionEntry(90, "sleep.start", requiresSession = false),
    ActionEntry(91, "sleep.up", requiresSession = false),
    ActionEntry(92, "sleep.rate", requiresSession = false),
    ActionEntry(93, "sleep.undo", requiresSession = false),
    ActionEntry(94, "sleep.start_undo", requiresSession = false),
    ActionEntry(95, "sleep.image", requiresSession = false),
    // Prompts and the /reminders hub (ADR-023): direct mode on the prompt occurrence for the buttons of a prompt, on
    // the account for the hub; the value carries the amount, the minutes, the kind, the preset or the hours.
    ActionEntry(100, "prompt.water_add", requiresSession = false),
    ActionEntry(101, "prompt.snooze", requiresSession = false),
    ActionEntry(102, "prompt.pause_today", requiresSession = false),
    ActionEntry(103, "prompt.sleep", requiresSession = false),
    ActionEntry(110, "reminders.hub", requiresSession = false),
    ActionEntry(111, "reminders.kind", requiresSession = false),
    ActionEntry(112, "reminders.preview", requiresSession = false),
    ActionEntry(113, "reminders.apply", requiresSession = false),
    ActionEntry(114, "reminders.off", requiresSession = false),
    ActionEntry(115, "reminders.pause_all", requiresSession = false),
    ActionEntry(116, "reminders.day", requiresSession = false)
  )

  require(entries.forall(e => e.id >= 0 && e.id <= 0xffff), "action ids are UInt16")
  require(entries.map(_.id).distinct.size == entries.size, "duplicate action id")
  require(entries.map(_.name).distinct.size == entries.size, "duplicate action name")

  private val byIdIndex: Map[Int, ActionEntry] = entries.map(e => e.id -> e).toMap

  def byId(id: Int): Option[ActionEntry] = byIdIndex.get(id)
  def byName(name: String): Option[ActionEntry] = entries.find(_.name == name)
end ActionRegistry
