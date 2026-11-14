package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.core.ports.SealBroken
import dosegram.core.ports.SealedField
import dosegram.infra.crypto.DataKeys
import dosegram.infra.crypto.Kek
import dosegram.infra.crypto.KekRing
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import scala.language.implicitConversions

/** ROADMAP G2.4 on Testcontainers Postgres 18: the key service of ARCHITECTURE section 15.2. Envelope encryption round
  * trips; a value moved to another account, table, column or row, or changed by one bit, does not open (SEC-01); a
  * wrong key-encryption key opens nothing; a data key rotates without losing a value and the key-encryption key rotates
  * by rewrapping (SEC-07); an unwrapped key leaves memory after five minutes (SEC-03).
  */
class KeyServicePgSuite extends PgSuite, ScalaCheckSuite:

  private val t0 = Instant.parse("2026-11-14T08:00:00Z")
  private lazy val fixtures = Fixtures(dataSource)

  private def material(seed: Int): Array[Byte] = Array.tabulate[Byte](Kek.Bytes)(i => (i * 7 + seed).toByte)

  private def ring(id: String, seed: Int, retired: Kek*): KekRing = KekRing(Kek(id, material(seed)), retired.toList)

  /** One committed transaction on a fresh connection with `keys`. */
  private def inTx[A](keys: DataKeys)(f: (Connection, dosegram.core.ports.KeyService) => A): A =
    withConnection: conn =>
      conn.setAutoCommit(false)
      try
        val result = f(conn, keys.on(conn))
        conn.commit()
        result
      catch
        case e: Throwable =>
          conn.rollback()
          throw e

  /** The audit rows about `account`, oldest first, as event type, actor and object. */
  private def keyEvents(account: UUID): List[String] =
    withConnection: conn =>
      given Connection = conn
      sql"""SELECT event_type || ' ' || actor_type || ':' || actor_ref || ' ' || object_type || ' ' || action
            FROM audit_log WHERE subject_ref = $account ORDER BY occurred_at, id""".query[String]()

  private def kekRotations(): Int =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT count(*)::int FROM audit_log WHERE event_type = 'key.kek.rotated'".queryOne[Int]().getOrElse(0)

  private def field(account: UUID, row: String = "row-1", column: String = "name_enc", table: String = "medications") =
    SealedField(KeyOwner.Account(account), table, column, row)

  property("a sealed value opens to the same bytes, and two seals of one value differ"):
    val keys = DataKeys(ring("p1", 1), MutableClock(t0))
    val account = fixtures.account()
    forAll(Gen.asciiPrintableStr) { (text: String) =>
      inTx(keys): (_, service) =>
        val first = service.seal(field(account), text.getBytes(UTF_8))
        val second = service.seal(field(account), text.getBytes(UTF_8))
        assertEquals(String(service.open(field(account), first), UTF_8), text)
        assert(!first.sameElements(second), "a fresh nonce per value")
    }

  property("a value moved to another account, table, column or row does not open"):
    val keys = DataKeys(ring("p2", 2), MutableClock(t0))
    val account = fixtures.account()
    val other = fixtures.account()
    forAll(Gen.alphaNumStr) { (text: String) =>
      inTx(keys): (_, service) =>
        val value = service.seal(field(account), text.getBytes(UTF_8))
        List(
          field(other),
          field(account, table = "meals"),
          field(account, column = "instructions_enc"),
          field(account, row = "row-2")
        ).foreach(moved => intercept[SealBroken](service.open(moved, value)))
    }

  test("a changed bit, a cut value and a value that was never sealed do not open"):
    val keys = DataKeys(ring("p3", 3), MutableClock(t0))
    val account = fixtures.account()
    inTx(keys): (_, service) =>
      val value = service.seal(field(account), "canary-tamper".getBytes(UTF_8))
      value.indices.foreach: i =>
        val flipped = value.clone()
        flipped(i) = (flipped(i) ^ 0x01).toByte
        intercept[SealBroken](service.open(field(account), flipped))
      intercept[SealBroken](service.open(field(account), value.take(value.length - 1)))
      intercept[SealBroken](service.open(field(account), "canary-tamper".getBytes(UTF_8)))

  test("another key-encryption key opens nothing, and a missing one is named by its id only"):
    val account = fixtures.account()
    val value = inTx(DataKeys(ring("owner", 4), MutableClock(t0)))((_, s) => s.seal(field(account), Array[Byte](1, 2)))
    val sameIdOtherKey = DataKeys(ring("owner", 99), MutableClock(t0))
    intercept[SealBroken](inTx(sameIdOtherKey)((_, s) => s.open(field(account), value)))
    val unknown = intercept[IllegalStateException]:
      inTx(DataKeys(ring("stranger", 4), MutableClock(t0)))((_, s) => s.open(field(account), value))
    assert(unknown.getMessage.contains("'owner'"), unknown.getMessage)
    assert(!unknown.getMessage.contains(material(4).mkString), unknown.getMessage)

  test("a data key rotates: old values keep opening, new values use the new version, the lookup hash stays"):
    val clock = MutableClock(t0)
    val keys = DataKeys(ring("rot", 5), clock)
    val account = fixtures.account()
    val (before, hashBefore) = inTx(keys)((_, s) => (s.seal(field(account), Array[Byte](7)), s.lookupHash(account, "p", "x")))
    clock.advance(Duration.ofSeconds(1))
    assertEquals(inTx(keys)((conn, _) => keys.rotate(conn, KeyOwner.Account(account))), 2)
    val (after, hashAfter) = inTx(keys)((_, s) => (s.seal(field(account), Array[Byte](8)), s.lookupHash(account, "p", "x")))
    assertEquals(after.slice(1, 5).toList, List[Byte](0, 0, 0, 2), "the header carries the key version")
    assertEquals(before.slice(1, 5).toList, List[Byte](0, 0, 0, 1))
    inTx(keys): (_, s) =>
      assertEquals(s.open(field(account), before).toList, List[Byte](7))
      assertEquals(s.open(field(account), after).toList, List[Byte](8))
    assertEquals(hashAfter.toList, hashBefore.toList)
    assertEquals(
      keyEvents(account),
      List("key.dek.created system:keys data_key create", "key.dek.rotated system:keys data_key rotate"),
      "the first key and the rotation are audited, on the account, by the key service"
    )

  test("the key-encryption key rotates by rewrapping every data key; then the old key can go"):
    val old = Kek("kek-old", material(6))
    val oldOnly = DataKeys(KekRing(old, Nil), MutableClock(t0))
    val accounts = List.fill(3)(fixtures.account())
    val values = accounts.map(a => a -> inTx(oldOnly)((_, s) => s.seal(field(a), a.toString.getBytes(UTF_8))))
    // The other tests of this suite wrapped their keys with other key-encryption keys, which this ring does not hold.
    withConnection: conn =>
      given Connection = conn
      sql"DELETE FROM account_keys WHERE kek_id <> ${old.id}".execute()
    val rotating = DataKeys(KekRing(Kek("kek-new", material(7)), List(old)), MutableClock(t0))
    val rotationsBefore = kekRotations()
    val rewrapped = inTx(rotating)((conn, _) => rotating.rewrapRetired(conn))
    assert(rewrapped >= accounts.size, s"rewrapped $rewrapped")
    assertEquals(kekRotations(), rotationsBefore + 1, "one audit row for the rotation")
    assertEquals(inTx(rotating)((conn, _) => rotating.rewrapRetired(conn)), 0, "a second run finds nothing")
    assertEquals(kekRotations(), rotationsBefore + 1, "a run that rewraps nothing writes no audit row")
    val newOnly = DataKeys(KekRing(Kek("kek-new", material(7)), Nil), MutableClock(t0))
    values.foreach: (account, value) =>
      assertEquals(String(inTx(newOnly)((_, s) => s.open(field(account), value)), UTF_8), account.toString)

  test("an unwrapped data key stays in memory for at most five minutes (SEC-03)"):
    val clock = MutableClock(t0)
    val keys = DataKeys(ring("ttl", 8), clock)
    val account = fixtures.account()
    val value = inTx(keys)((_, s) => s.seal(field(account), Array[Byte](3)))
    inTx(keys)((_, s) => s.open(field(account), value)) // loads the key into the cache
    withConnection: conn =>
      given Connection = conn
      sql"UPDATE account_keys SET wrapped_key = ${Array[Byte](0)} WHERE account_id = $account".execute()
    clock.advance(Duration.ofMinutes(4))
    assertEquals(inTx(keys)((_, s) => s.open(field(account), value)).toList, List[Byte](3), "still cached")
    clock.advance(Duration.ofMinutes(1))
    intercept[SealBroken](inTx(keys)((_, s) => s.open(field(account), value)))

  test("a key created in a transaction that rolls back is neither stored nor kept in memory"):
    val keys = DataKeys(ring("rb", 9), MutableClock(t0))
    val account = fixtures.account()
    var lost = Array.emptyByteArray
    intercept[IllegalStateException]:
      inTx(keys): (_, s) =>
        lost = s.seal(field(account), Array[Byte](1))
        throw IllegalStateException("roll back")
    val kept = inTx(keys)((_, s) => s.seal(field(account), Array[Byte](2)))
    inTx(keys): (_, s) =>
      assertEquals(s.open(field(account), kept).toList, List[Byte](2))
      intercept[SealBroken](s.open(field(account), lost))
    val stored = withConnection: conn =>
      given Connection = conn
      sql"SELECT count(*)::int FROM account_keys WHERE account_id = $account".queryOne[Int]()
    assertEquals(stored, Some(1))
    assertEquals(keyEvents(account), List("key.dek.created system:keys data_key create"), "the rolled-back key left no row")

  test("a forgotten owner's keys leave memory at once: once its key rows are gone, nothing opens"):
    val keys = DataKeys(ring("fg", 12), MutableClock(t0))
    val account = fixtures.account()
    val value = inTx(keys)((_, s) => s.seal(field(account), Array[Byte](4)))
    assertEquals(inTx(keys)((_, s) => s.open(field(account), value)).toList, List[Byte](4), "cached")
    withConnection: conn =>
      given Connection = conn
      sql"DELETE FROM account_keys WHERE account_id = $account".execute()
    keys.forget(KeyOwner.Account(account))
    intercept[SealBroken](inTx(keys)((_, s) => s.open(field(account), value)))

  test("two transactions that create an account's first key at once store one key and both values open"):
    val keys = DataKeys(ring("race", 10), MutableClock(t0))
    val account = fixtures.account()
    val barrier = CyclicBarrier(2)
    val pool = Executors.newFixedThreadPool(2)
    try
      val futures = List(1, 2).map: n =>
        pool.submit(() =>
          inTx(keys): (_, s) =>
            barrier.await(10, TimeUnit.SECONDS)
            s.seal(field(account, row = s"r$n"), Array[Byte](n.toByte))
        )
      val values = futures.map(_.get(30, TimeUnit.SECONDS))
      inTx(keys): (_, s) =>
        values.zipWithIndex.foreach: (value, i) =>
          assertEquals(s.open(field(account, row = s"r${i + 1}"), value).toList, List((i + 1).toByte))
    finally pool.shutdown()
    val stored = withConnection: conn =>
      given Connection = conn
      sql"SELECT count(*)::int FROM account_keys WHERE account_id = $account".queryOne[Int]()
    assertEquals(stored, Some(1))

  test("a service key seals rows that belong to no account"):
    val keys = DataKeys(ring("svc", 11), MutableClock(t0))
    val place = SealedField(KeyOwner.Service("render"), "render_assets", "bytes_enc", "card.1")
    val value = inTx(keys)((_, s) => s.seal(place, Array[Byte](5)))
    assertEquals(inTx(keys)((_, s) => s.open(place, value)).toList, List[Byte](5))
    intercept[SealBroken](inTx(keys)((_, s) => s.open(place.copy(row = "card.2"), value)))
