package dosegram.core.domain.copy

/** Catch-up digest copy (DESIGN.md section 7.5): one digest per account per 15-minute bucket after downtime. */
object DigestCopy:

  def header(count: Int): String =
    tr(
      s"While I was away, ${counted(count, "dose", "doses", Doses)} passed without a reminder.",
      s"Поки мене не було, без нагадування ${Passed(count)} ${Doses.count(count)}."
    )

  /** One digest line: `• Vitamin D, 1000 IU at 09:00` (the occurrence's own zone). */
  def item(name: String, dose: Option[String], time: String): String =
    dose match
      case Some(d) => tr(s"• $name, $d at $time", s"• $name, $d о $time")
      case None    => tr(s"• $name at $time", s"• $name о $time")

  /** The digest shows at most [[DigestMaxItems]] doses with controls (the 8 x 3 native-component cap of
    * `core/chat/Renderer.scala`); the rest are counted in one closing line.
    */
  def more(count: Int): String =
    tr(
      s"…and ${counted(count, "more dose", "more doses", Doses)} passed.",
      s"…і ще ${Doses.count(count)} ${Passed(count)} без нагадування."
    )

  private val Doses = UkForms("доза", "дози", "доз")
  private val Passed = UkForms("минула", "минули", "минуло")

  /** Doses with controls per digest message (DESIGN.md section 7.5; the renderer pages longer control sets, and the
    * outbox protocol is one row, one message).
    */
  val DigestMaxItems = 8

  def entries: List[CopyEntry] = List(
    CopyEntry("digest.header", header(9)),
    CopyEntry("digest.header.singular", header(1)),
    CopyEntry("digest.item", item("Vitamin D", Some("1000 IU"), "09:00")),
    CopyEntry("digest.item.no_dose", item("Vitamin D", None, "09:00")),
    CopyEntry("digest.more", more(2))
  )
end DigestCopy
