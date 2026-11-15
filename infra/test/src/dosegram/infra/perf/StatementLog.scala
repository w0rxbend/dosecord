package dosegram.infra.perf

import com.dimafeng.testcontainers.PostgreSQLContainer

import javax.sql.DataSource

/** The SQL statements a probe's database ran while `f` ran, with the server time of each, read from the server log of
  * the probe's own Testcontainers Postgres with `log_min_duration_statement = 0`: how many queries one request or one
  * tick costs, and which of them the server spends its time on (docs/PERFORMANCE.md). Only the statement text up to
  * its first line break is kept; parameters are never read.
  */
object StatementLog:

  private val Logged = """.*LOG:  duration: ([0-9.]+) ms  (?:execute [^:]*|statement): (.*)""".r

  /** One statement text: how often it ran and the server milliseconds it took in all. */
  final case class Statement(text: String, count: Int, serverMillis: Double)

  final case class Counted(statements: List[Statement]):
    def total: Int = statements.map(_.count).sum
    def serverMillis: Double = statements.map(_.serverMillis).sum

    def top(n: Int): String =
      statements
        .take(n)
        .map(s => f"${s.count}%5d ${s.serverMillis}%9.1f ms  ${s.text}")
        .mkString("\n")

  def during(pg: PostgreSQLContainer, dataSource: DataSource)(f: => Unit): Counted =
    setLogging(dataSource, "0")
    val before = pg.container.getLogs.length
    f
    Thread.sleep(300) // the server writes its log asynchronously
    setLogging(dataSource, "-1")
    val logged = pg.container.getLogs.substring(before).linesIterator.collect:
      case Logged(millis, text) => (text.take(100), millis.toDouble)
    val statements = logged.toList
      .groupBy(_._1)
      .map((text, runs) => Statement(text, runs.size, runs.map(_._2).sum))
      .toList
      .filterNot(_.text.startsWith("ALTER SYSTEM"))
      .sortBy(-_.serverMillis)
    Counted(statements)

  private def setLogging(dataSource: DataSource, minDurationMillis: String): Unit =
    val conn = dataSource.getConnection
    try
      val st = conn.createStatement()
      try
        st.execute(s"ALTER SYSTEM SET log_min_duration_statement = $minDurationMillis")
        st.execute("SELECT pg_reload_conf()")
      finally st.close()
    finally conn.close()
    Thread.sleep(200)
