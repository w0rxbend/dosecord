package dosecord.core.domain.copy

/** Catch-up digest copy (DESIGN.md section 7.5): one digest per account per 15-minute bucket after downtime. */
object DigestCopy:

  def header(count: Int): String =
    val noun = if count == 1 then "dose" else "doses"
    s"While I was away, $count $noun passed without a reminder."

  val entries: List[CopyEntry] = List(
    CopyEntry("digest.header", header(9)),
    CopyEntry("digest.header.singular", header(1))
  )
end DigestCopy
