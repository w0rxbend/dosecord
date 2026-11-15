package dosegram.infra.db

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection

/** The WAL half of the dump test of ROADMAP G2.4b: reads the server's WAL segment files through `pg_read_binary_file`
  * (a superuser function; the login of the test container is one), drops each page header so that a record crossing a
  * page boundary reads as one run of bytes, and searches the result for each canary's UTF-8 bytes. A value sealed
  * before it reaches the database is never in the WAL; a plaintext value is, in the record that wrote it and in every
  * full-page image of its page until the page is pruned.
  */
object WalScan:

  /** A WAL position, as `pg_current_wal_lsn()` prints it. */
  final case class Lsn(value: Long):
    override def toString: String = f"${value >>> 32}%X/${value & 0xffffffffL}%X"

  object Lsn:
    def parse(text: String): Lsn =
      val Array(high, low) = text.split('/')
      Lsn((java.lang.Long.parseLong(high, 16) << 32) | java.lang.Long.parseLong(low, 16))

  // PostgreSQL's SizeOfXLogLongPHD (the first page of a segment) and SizeOfXLogShortPHD (every other page).
  private val LongHeader = 40
  private val ShortHeader = 24
  private val Chunk = 1 << 20

  /** The end of the WAL written so far, on disk: a committed transaction flushes every record before its commit. */
  def flushed(conn: Connection): Lsn =
    val autoCommit = conn.getAutoCommit
    conn.setAutoCommit(true)
    try
      single(conn, "SELECT txid_current()::text")
      Lsn.parse(single(conn, "SELECT pg_current_wal_flush_lsn()::text"))
    finally conn.setAutoCommit(autoCommit)

  /** The canaries found in the WAL written from `from` up to `to`. */
  def between(conn: Connection, from: Lsn, to: Lsn, canaries: List[String]): List[String] =
    val layout = Layout.of(conn)
    val out = ByteArrayOutputStream()
    var page = from.value / layout.block * layout.block
    while page < to.value do
      val segment = page / layout.segment
      val end = math.min((segment + 1) * layout.segment, ceil(to.value, layout.block))
      val bytes = read(conn, layout.fileName(segment), page % layout.segment, end - page)
      layout.strip(bytes, page, from.value, to.value, out)
      page = end
    found(out.toByteArray, canaries)

  /** The canaries found anywhere in the segment files of `pg_wal`: recycled files, which keep the bytes they held until
    * they are written over, included.
    */
  def directory(conn: Connection, canaries: List[String]): List[String] =
    val layout = Layout.of(conn)
    val files = rows(conn, "SELECT name FROM pg_ls_waldir() WHERE name ~ '^[0-9A-F]{24}$' ORDER BY name")
    files
      .flatMap: name =>
        val out = ByteArrayOutputStream()
        val first = layout.segmentOf(name) * layout.segment
        (0L until layout.segment by Chunk.toLong).foreach: offset =>
          val bytes = read(conn, name, offset, Chunk.toLong)
          layout.strip(bytes, first + offset, first, first + layout.segment, out)
        found(out.toByteArray, canaries)
      .distinct

  /** The WAL file names and page sizes of the server. */
  private final case class Layout(timeline: Long, segment: Long, block: Long):
    private val segmentsPerId = 0x100000000L / segment

    def fileName(segment: Long): String = f"$timeline%08X${segment / segmentsPerId}%08X${segment % segmentsPerId}%08X"

    def segmentOf(name: String): Long =
      java.lang.Long.parseLong(name.substring(8, 16), 16) * segmentsPerId + java.lang.Long.parseLong(name.substring(16), 16)

    /** Appends the record bytes of `bytes`, which start at WAL position `start`, that lie in `[from, to)`. */
    def strip(bytes: Array[Byte], start: Long, from: Long, to: Long, out: ByteArrayOutputStream): Unit =
      var offset = 0L
      while offset < bytes.length do
        val address = start + offset
        val header = if address % segment == 0 then LongHeader else ShortHeader
        val low = math.max(header.toLong, from - address)
        val high = math.min(math.min(block, bytes.length - offset), to - address)
        if low < high then out.write(bytes, (offset + low).toInt, (high - low).toInt)
        offset += block

  private object Layout:
    def of(conn: Connection): Layout =
      Layout(
        java.lang.Long.parseLong(single(conn, "SELECT pg_walfile_name(pg_current_wal_lsn())").take(8), 16),
        single(conn, "SELECT setting FROM pg_settings WHERE name = 'wal_segment_size'").toLong,
        single(conn, "SELECT setting FROM pg_settings WHERE name = 'wal_block_size'").toLong
      )

  private def ceil(value: Long, unit: Long): Long = (value + unit - 1) / unit * unit

  private def read(conn: Connection, file: String, offset: Long, length: Long): Array[Byte] =
    val ps = conn.prepareStatement("SELECT pg_read_binary_file('pg_wal/' || ?, ?, ?)")
    try
      ps.setString(1, file)
      ps.setLong(2, offset)
      ps.setLong(3, length)
      val rs = ps.executeQuery()
      rs.next()
      rs.getBytes(1)
    finally ps.close()

  private def found(bytes: Array[Byte], canaries: List[String]): List[String] =
    canaries.filter(canary => indexOf(bytes, canary.getBytes(UTF_8)) >= 0)

  private def indexOf(haystack: Array[Byte], needle: Array[Byte]): Int =
    val last = haystack.length - needle.length
    var i = 0
    var at = -1
    while at < 0 && i <= last do
      var j = 0
      while j < needle.length && haystack(i + j) == needle(j) do j += 1
      if j == needle.length then at = i
      i += 1
    at

  private def single(conn: Connection, query: String): String = rows(conn, query).head

  // Every query here is a constant of this object, never input.
  private def rows(conn: Connection, query: String): List[String] =
    val st = conn.createStatement()
    try
      val rs = st.executeQuery(query)
      Iterator.continually(rs).takeWhile(_.next()).map(_.getString(1)).toList
    finally st.close()
