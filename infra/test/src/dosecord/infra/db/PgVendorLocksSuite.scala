package dosecord.infra.db

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Vendor ownership via advisory lock (ROADMAP M1.7, DESIGN.md section 3): one dispatcher instance per vendor — a
  * second process cannot take the same vendor lock, blocks until the first releases, and proceeds afterwards.
  */
class PgVendorLocksSuite extends PgSuite:

  test("a held vendor lock rejects a second taker until it is released"):
    val locks = PgVendorLocks(dataSource, Seq("fake"))
    locks.lockAll()
    try
      withFreshConnection { probe =>
        val rs = probe
          .createStatement()
          .executeQuery(s"SELECT pg_try_advisory_lock(hashtext('${PgVendorLocks.key("fake")}'))")
        rs.next()
        assert(!rs.getBoolean(1), "the vendor lock is held: a second process must not acquire it")
      }
    finally locks.close()
    withFreshConnection { probe =>
      val rs = probe
        .createStatement()
        .executeQuery(s"SELECT pg_try_advisory_lock(hashtext('${PgVendorLocks.key("fake")}'))")
      rs.next()
      assert(rs.getBoolean(1), "the vendor lock is released on close")
    }

  test("a second dispatcher blocks in lockAll until the first releases the vendor"):
    val first = PgVendorLocks(dataSource, Seq("fake"))
    first.lockAll()
    val secondPool = Database.pooled(pg.jdbcUrl, pg.username, pg.password)
    try
      val acquired = AtomicBoolean(false)
      val failure = AtomicReference[Throwable](null)
      val contender = new Thread(() =>
        try
          val second = PgVendorLocks(secondPool, Seq("fake"))
          second.lockAll()
          acquired.set(true)
          second.close()
        catch case e: Throwable => failure.set(e)
      )
      contender.start()
      Thread.sleep(1000)
      assert(!acquired.get(), "lockAll proceeded while another process held the vendor lock")

      first.close()
      contender.join(20000)
      assert(failure.get() == null, s"the contender failed: ${failure.get()}")
      assert(acquired.get(), "the contender acquired the vendor lock after the release")
    finally secondPool.close()
