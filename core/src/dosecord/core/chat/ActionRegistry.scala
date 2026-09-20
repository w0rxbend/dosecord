package dosecord.core.chat

enum Visibility:
  case Persistent, Ephemeral

final case class ActionEntry(
    id: Int,
    name: String,
    opensForm: Boolean,
    visibility: Visibility,
    requiresSession: Boolean
)

// Static action registry of DESIGN.md section 4.4 / ADR-006. The flags drive
// the acknowledgement policy of ADR-013: opensForm actions are never deferred,
// visibility declares the reply flag, requiresSession marks wizard-bound steps.
object ActionRegistry:
  val entries: List[ActionEntry] = List(
    ActionEntry(1, "dose.taken", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(2, "dose.snooze", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(3, "dose.skip", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(4, "dose.undo", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(5, "dose.note", opensForm = true, Visibility.Persistent, requiresSession = false),
    // dose.correct is a two-choice button row (M1.10, M2.2), not a modal.
    ActionEntry(6, "dose.correct", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(7, "dose.keep_missed", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(20, "menu.open", opensForm = false, Visibility.Persistent, requiresSession = false),
    ActionEntry(30, "wizard.step", opensForm = false, Visibility.Persistent, requiresSession = true),
    ActionEntry(31, "wizard.text_step", opensForm = true, Visibility.Persistent, requiresSession = true),
    ActionEntry(32, "wizard.confirm", opensForm = false, Visibility.Persistent, requiresSession = true),
    ActionEntry(40, "link.confirm", opensForm = false, Visibility.Ephemeral, requiresSession = false)
  )

  require(entries.forall(e => e.id >= 0 && e.id <= 0xffff), "action ids are UInt16")
  require(entries.map(_.id).distinct.size == entries.size, "duplicate action id")
  require(entries.map(_.name).distinct.size == entries.size, "duplicate action name")

  private val byIdIndex: Map[Int, ActionEntry] = entries.map(e => e.id -> e).toMap

  def byId(id: Int): Option[ActionEntry] = byIdIndex.get(id)
  def byName(name: String): Option[ActionEntry] = entries.find(_.name == name)
end ActionRegistry
