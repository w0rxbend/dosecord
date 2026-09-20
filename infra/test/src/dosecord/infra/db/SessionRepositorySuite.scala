package dosecord.infra.db

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

import dosecord.core.ports.Clock
import dosecord.core.ports.ConversationSession
import dosecord.core.ports.StaleSessionVersion

class SessionRepositorySuite extends PgSuite:

  private val start = Instant.parse("2026-09-20T00:00:00Z")

  /** Deterministic clock advancing one minute per call, so `updated_at` always strictly advances (K7).
    */
  private def tickingClock: Clock =
    val ticks = Iterator.iterate(start)(_.plusSeconds(60))
    () => ticks.next()

  private def newSession(id: UUID, principalKey: String, version: Int = 1, createdAt: Instant = start) =
    ConversationSession(
      id = id,
      principalKey = principalKey,
      vendor = "console",
      chatId = "chat-1",
      flow = "create",
      step = "timezone",
      stepSeq = 0,
      data = "{}",
      version = version,
      lastPrompt = None,
      expiresAt = start.plusSeconds(3600),
      createdAt = createdAt,
      updatedAt = createdAt
    )

  test("insert, FOR UPDATE load, versioned save; stale version rejected"):
    val uow = PgUnitOfWork(dataSource, tickingClock)
    val id = UUID.randomUUID()
    uow.transaction(_.sessions.insert(newSession(id, "principal-1")))

    uow.transaction { tx =>
      val loaded = tx.sessions.loadForUpdate("principal-1", "console", "chat-1")
      assert(loaded.isDefined)
      assertEquals(loaded.get.step, "timezone")
      tx.sessions.save(loaded.get.copy(step = "confirm", stepSeq = 1))
    }

    val after = uow.transaction(_.sessions.loadForUpdate("principal-1", "console", "chat-1")).get
    assertEquals(after.version, 2)
    assertEquals(after.step, "confirm")
    assert(after.updatedAt.isAfter(after.createdAt), "updated_at must advance on every step")

    intercept[StaleSessionVersion]:
      uow.transaction(_.sessions.save(newSession(id, "principal-1").copy(step = "stale")))

  test("loadForUpdate holds the row lock until commit"):
    val uow = PgUnitOfWork(dataSource, tickingClock)
    val id = UUID.randomUUID()
    uow.transaction(_.sessions.insert(newSession(id, "principal-2")))

    val conn = dataSource.getConnection
    conn.setAutoCommit(false)
    val lockedRepo = PgSessionRepository(conn, tickingClock)
    assert(lockedRepo.loadForUpdate("principal-2", "console", "chat-1").isDefined)

    val acquired = AtomicBoolean(false)
    val contender = new Thread(() =>
      uow.transaction { tx =>
        tx.sessions.loadForUpdate("principal-2", "console", "chat-1")
        acquired.set(true)
      }
    )
    contender.start()
    Thread.sleep(300)
    assert(!acquired.get(), "second transaction must block on FOR UPDATE")
    conn.commit()
    conn.close()
    contender.join(10000)
    assert(acquired.get(), "second transaction must proceed after commit")
