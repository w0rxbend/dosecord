package dosecord.infra.db

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** `dosecord migrate` serialises on `pg_advisory_lock` (ROADMAP M0.5 acceptance). */
class AdvisoryLockSuite extends PgSuite:

  test("a second migrateLocked blocks until the first lock holder releases"):
    withFreshConnection { locker =>
      val stmt = locker.createStatement()
      stmt.execute(s"SELECT pg_advisory_lock(${Migrator.AdvisoryLockKey})")

      val completed = AtomicBoolean(false)
      val failure   = AtomicReference[Throwable](null)
      val contender = new Thread(() =>
        try
          Migrator(dataSource).migrateLocked()
          completed.set(true)
        catch case e: Throwable => failure.set(e)
      )
      contender.start()

      Thread.sleep(1000)
      assert(!completed.get(), "migrateLocked proceeded while the advisory lock was held by another session")
      withFreshConnection { probe =>
        val rs = probe.createStatement().executeQuery(s"SELECT pg_try_advisory_lock(${Migrator.AdvisoryLockKey})")
        rs.next()
        assert(!rs.getBoolean(1), "advisory lock was free while the contender was waiting")
      }

      stmt.execute(s"SELECT pg_advisory_unlock(${Migrator.AdvisoryLockKey})")
      stmt.close()
      contender.join(20000)
      assert(failure.get() == null, s"migrateLocked failed: ${failure.get()}")
      assert(completed.get(), "migrateLocked did not finish after the lock was released")
    }

  test("two parallel migrateLocked invocations serialise and both succeed"):
    val secondPool = Database.pooled(pg.jdbcUrl, pg.username, pg.password)
    try
      val failure = AtomicReference[Throwable](null)
      val other = new Thread(() =>
        try
          Migrator(secondPool).migrateLocked()
          ()
        catch case e: Throwable => failure.set(e)
      )
      other.start()
      Migrator(dataSource).migrateLocked()
      other.join(20000)
      assert(!other.isAlive, "parallel migrateLocked did not finish")
      assert(failure.get() == null, s"parallel migrateLocked failed: ${failure.get()}")
      Migrator(dataSource).validate()
    finally secondPool.close()
