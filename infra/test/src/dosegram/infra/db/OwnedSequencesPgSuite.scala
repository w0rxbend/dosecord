package dosegram.infra.db

import dosegram.infra.crypto.DataKeys

import java.sql.Connection

/** The live defect of 2026-11-14: rows copied into a new database with their `seq`, without `setval`, left
  * `domain_events_seq_seq` behind them; the day's reminder and two button taps failed on the primary key (SQL state
  * 23505) until the failures had used up the copied numbers. Start-up now moves such a sequence past its rows.
  */
class OwnedSequencesPgSuite extends PgSuite:

  private def run(conn: Connection, statement: String): Unit =
    val st = conn.createStatement()
    try st.execute(statement) // the suite's own statement
    finally st.close()

  private def nextSeq(conn: Connection): Long =
    val rs = conn.createStatement().executeQuery(
      """INSERT INTO domain_events (id, type, source, occurred_at, data)
         VALUES (gen_random_uuid(), 'probe.v1', 'test', now(), '{}') RETURNING seq"""
    )
    rs.next()
    rs.getLong(1)

  test("a start moves a sequence that copied rows left behind, and the next event is stored after them"):
    withConnection: conn =>
      run(conn, "TRUNCATE domain_events")
      run(
        conn,
        """INSERT INTO domain_events (seq, id, type, source, occurred_at, data)
           SELECT n, gen_random_uuid(), 'copied.v1', 'test', now(), '{}' FROM generate_series(1, 7) AS n"""
      )
      run(conn, "SELECT setval('domain_events_seq_seq', 1, false)")
      intercept[java.sql.SQLException](nextSeq(conn)) // what the live database did
    val logged = List.newBuilder[String]
    SchemaUpgrade.run(dataSource, DataKeys.development, line => logged += line)
    assert(
      logged.result().contains("sequences: domain_events_seq_seq was behind domain_events.seq; moved to 7"),
      logged.result().toString
    )
    withConnection(conn => assertEquals(nextSeq(conn), 8L))

  test("a sequence ahead of its rows, or of an empty table, is left as it is"):
    withConnection: conn =>
      run(conn, "TRUNCATE domain_events")
      assertEquals(OwnedSequences.align(conn), Nil)
      val next = nextSeq(conn)
      run(conn, "DELETE FROM domain_events")
      assertEquals(OwnedSequences.align(conn), Nil)
      assertEquals(nextSeq(conn), next + 1)
