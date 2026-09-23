package dosecord.core.domain.copy

/** Catch-up digest copy (DESIGN.md section 7.5): one digest per account per 15-minute bucket after downtime. */
object DigestCopy:

  def header(count: Int): String =
    val noun = if count == 1 then "dose" else "doses"
    s"While I was away, $count $noun passed without a reminder."

  /** One digest line: `• Vitamin D, 1000 IU at 09:00` (the occurrence's own zone). */
  def item(name: String, dose: Option[String], time: String): String =
    dose match
      case Some(d) => s"• $name, $d at $time"
      case None    => s"• $name at $time"

  /** The digest shows at most [[DigestMaxItems]] doses with controls (the 8 x 3 native-component cap of
    * `core/chat/Renderer.scala`); the rest are counted in one closing line.
    */
  def more(count: Int): String =
    val noun = if count == 1 then "dose" else "doses"
    s"…and $count more $noun passed."

  /** Doses with controls per digest message (DESIGN.md section 7.5; the renderer pages longer control sets, and the
    * outbox protocol is one row, one message).
    */
  val DigestMaxItems = 8

  val entries: List[CopyEntry] = List(
    CopyEntry("digest.header", header(9)),
    CopyEntry("digest.header.singular", header(1)),
    CopyEntry("digest.item", item("Vitamin D", Some("1000 IU"), "09:00")),
    CopyEntry("digest.item.no_dose", item("Vitamin D", None, "09:00")),
    CopyEntry("digest.more", more(2))
  )
end DigestCopy
