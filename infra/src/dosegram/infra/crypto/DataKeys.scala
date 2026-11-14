package dosegram.infra.crypto

import dosegram.core.ports.Clock
import dosegram.core.ports.KeyOwner
import dosegram.core.ports.KeyService
import dosegram.core.ports.SealBroken
import dosegram.core.ports.SealedField
import dosegram.infra.db.RowMapper
import dosegram.infra.db.SqlBind
import dosegram.infra.db.SqlQuery

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.language.implicitConversions

/** One unwrapped data key of an owner. Its text form never shows the key. */
private[crypto] final class DataKey(val version: Int, val material: Array[Byte]):
  override def toString: String = s"DataKey(v$version, ***)"

/** The data keys of the process (ARCHITECTURE section 15.2): each owner's 256-bit keys are stored in `account_keys` or
  * `service_keys` wrapped by the key-encryption key, and unwrapped keys stay in a bounded cache for at most
  * [[DataKeys.CacheTtl]] (SEC-03). A key created inside a transaction is cached only once a later read finds it
  * committed, so a rolled-back transaction never leaves a key in memory that the database does not hold.
  */
final class DataKeys(val ring: KekRing, clock: Clock):
  import DataKeys.*

  private final case class Cached(key: DataKey, loadedAt: Instant)

  // Keyed by owner label and version; version 0 holds the owner's active key.
  private val cache = ConcurrentHashMap[(String, Int), Cached]()

  /** The key service bound to `conn`: data keys it creates commit or roll back with the caller's transaction. */
  def on(conn: Connection): KeyService = PgKeyService(conn)

  /** Adds a new active data key for `owner` (SEC-07, on demand). Values sealed before keep opening with their own
    * version; a value is sealed with the new key the next time it is written. Returns the new version.
    */
  def rotate(conn: Connection, owner: KeyOwner): Int =
    val store = Store.of(owner)
    val next = store.maxVersion(conn, owner) + 1
    store.insert(conn, owner, next, wrap(owner, next, Aead.freshKey()), clock.now())
    cache.remove((label(owner), 0))
    next

  /** Rewraps every data key that a retired key-encryption key wraps with the active one (SEC-07) and returns how many
    * it rewrapped. Refuses, naming the id only, a data key whose key-encryption key is not configured.
    */
  def rewrapRetired(conn: Connection): Int =
    Store.all.map(_.rewrap(conn, this)).sum

  private[crypto] def wrap(owner: KeyOwner, version: Int, material: Array[Byte]): Wrapped =
    Wrapped(ring.active.id, Aead.seal(ring.active.material, wrapAad(ring.active.id, owner, version), material))

  private[crypto] def unwrap(owner: KeyOwner, version: Int, wrapped: Wrapped): DataKey =
    val kek = ring
      .find(wrapped.kekId)
      .getOrElse(
        throw IllegalStateException(
          s"a data key (version $version) is wrapped by key-encryption key '${wrapped.kekId}', which is not configured"
        )
      )
    val material = Aead
      .open(kek.material, wrapAad(kek.id, owner, version), wrapped.bytes)
      .getOrElse(throw SealBroken(s"a data key (version $version) does not unwrap with key '${kek.id}'"))
    DataKey(version, material)

  private def fromCache(owner: KeyOwner, version: Int): Option[DataKey] =
    Option(cache.get((label(owner), version))).collect:
      case Cached(key, loadedAt) if Duration.between(loadedAt, clock.now()).compareTo(CacheTtl) < 0 => key

  private def remember(owner: KeyOwner, slot: Int, key: DataKey): Unit =
    if cache.size >= CacheEntries then cache.clear()
    cache.put((label(owner), slot), Cached(key, clock.now()))

  private final class PgKeyService(conn: Connection) extends KeyService:
    // Keys this transaction created: not committed yet, so never in the shared cache.
    private val created = mutable.Map.empty[String, DataKey]

    override def seal(field: SealedField, plaintext: Array[Byte]): Array[Byte] =
      val key = active(field.owner)
      val header = ByteBuffer.allocate(HeaderBytes).put(Format).putInt(key.version).array()
      header ++ Aead.seal(key.material, fieldAad(field, key.version), plaintext)

    override def open(field: SealedField, value: Array[Byte]): Array[Byte] =
      if value.length <= HeaderBytes || value(0) != Format then throw SealBroken("not a sealed value")
      val version = ByteBuffer.wrap(value, 1, 4).getInt
      val key = byVersion(field.owner, version)
        .getOrElse(throw SealBroken(s"no data key of version $version for this value"))
      Aead
        .open(key.material, fieldAad(field, version), value.drop(HeaderBytes))
        .getOrElse(throw SealBroken(s"a value of ${field.table}.${field.column} does not open"))

    override def lookupHash(account: UUID, purpose: String, value: String): Array[Byte] =
      val owner = KeyOwner.Account(account)
      // Bound to the first data key, so the lookup column survives a rotation of the data key.
      val first = byVersion(owner, 1).getOrElse(active(owner))
      val lookupKey = Aead.hmac(first.material, LookupLabel)
      Aead.hmac(lookupKey, s"$purpose\u0000$value".getBytes(UTF_8))

    private def active(owner: KeyOwner): DataKey =
      created
        .get(label(owner))
        .orElse(fromCache(owner, 0))
        .orElse(load(owner, None, slot = 0))
        .getOrElse(create(owner))

    private def byVersion(owner: KeyOwner, version: Int): Option[DataKey] =
      created
        .get(label(owner))
        .filter(_.version == version)
        .orElse(fromCache(owner, version))
        .orElse(load(owner, Some(version), slot = version))

    private def load(owner: KeyOwner, version: Option[Int], slot: Int): Option[DataKey] =
      Store
        .of(owner)
        .find(conn, owner, version)
        .map: (found, wrapped) =>
          val key = unwrap(owner, found, wrapped)
          remember(owner, slot, key)
          remember(owner, found, key)
          key

    /** The first key of an owner. Of two transactions that create it at once, the second waits for the first and uses
      * its key once it commits.
      */
    private def create(owner: KeyOwner): DataKey =
      val store = Store.of(owner)
      val wrapped = wrap(owner, 1, Aead.freshKey())
      store.insert(conn, owner, 1, wrapped, clock.now())
      val (version, stored) =
        store.find(conn, owner, None).getOrElse(throw IllegalStateException("a data key vanished"))
      val key = unwrap(owner, version, stored)
      if stored.bytes.sameElements(wrapped.bytes) then created.put(label(owner), key)
      else remember(owner, 0, key)
      key

object DataKeys:

  /** SEC-03: an unwrapped data key stays in memory for at most five minutes. */
  val CacheTtl: Duration = Duration.ofMinutes(5)

  /** Bound of the cache in entries; when it is full the cache starts again empty. */
  val CacheEntries: Int = 4096

  /** The keys of development and tests, wrapped by [[KekRing.development]]. */
  def development: DataKeys = DataKeys(KekRing.development, Clock.system)

  private val Format: Byte = 1
  private val HeaderBytes = 5
  private val LookupLabel = "dosegram/lookup/v1".getBytes(UTF_8)

  private[crypto] final case class Wrapped(kekId: String, bytes: Array[Byte])

  private def label(owner: KeyOwner): String = owner match
    case KeyOwner.Account(id)      => s"account:$id"
    case KeyOwner.Service(purpose) => s"service:$purpose"

  private def wrapAad(kekId: String, owner: KeyOwner, version: Int): Array[Byte] =
    s"dosegram/key/v1\u0000$kekId\u0000${label(owner)}\u0000$version".getBytes(UTF_8)

  private def fieldAad(field: SealedField, version: Int): Array[Byte] =
    List("dosegram/field/v1", label(field.owner), field.table, field.column, field.row, version.toString)
      .mkString("\u0000")
      .getBytes(UTF_8)

  /** The table that holds one kind of owner's wrapped keys. Table and column names are constants of this file. */
  private final class Store(table: String, ownerColumn: String, ownerOf: String => KeyOwner, bind: KeyOwner => SqlBind):
    private given RowMapper[(Int, Wrapped)] = rs =>
      (rs.getInt("version"), Wrapped(rs.getString("kek_id"), rs.getBytes("wrapped_key")))

    /** The owner's key of `version`, or its active (highest) key. */
    def find(conn: Connection, owner: KeyOwner, version: Option[Int]): Option[(Int, Wrapped)] =
      given Connection = conn
      val versionFilter = version.fold("")(_ => " AND version = ?")
      SqlQuery(
        s"SELECT version, kek_id, wrapped_key FROM $table WHERE $ownerColumn = ?$versionFilter " +
          "ORDER BY version DESC LIMIT 1",
        Vector(bind(owner)) ++ version.map(v => v: SqlBind)
      ).queryOne[(Int, Wrapped)]()

    def maxVersion(conn: Connection, owner: KeyOwner): Int = find(conn, owner, None).fold(0)(_._1)

    def insert(conn: Connection, owner: KeyOwner, version: Int, wrapped: Wrapped, at: Instant): Unit =
      given Connection = conn
      SqlQuery(
        s"INSERT INTO $table ($ownerColumn, version, kek_id, wrapped_key, created_at) VALUES (?, ?, ?, ?, ?) " +
          "ON CONFLICT DO NOTHING",
        Vector(bind(owner), version, wrapped.kekId, wrapped.bytes, at)
      ).execute()
      ()

    def rewrap(conn: Connection, keys: DataKeys): Int =
      given Connection = conn
      given RowMapper[(String, Int, Wrapped)] = rs =>
        (rs.getString(1), rs.getInt(2), Wrapped(rs.getString(3), rs.getBytes(4)))
      val stale = SqlQuery(
        s"SELECT $ownerColumn::text, version, kek_id, wrapped_key FROM $table WHERE kek_id <> ? FOR UPDATE",
        Vector(keys.ring.active.id)
      ).query[(String, Int, Wrapped)]()
      stale.foreach: (ownerText, version, wrapped) =>
        val owner = ownerOf(ownerText)
        val fresh = keys.wrap(owner, version, keys.unwrap(owner, version, wrapped).material)
        SqlQuery(
          s"UPDATE $table SET kek_id = ?, wrapped_key = ? WHERE $ownerColumn = ? AND version = ?",
          Vector(fresh.kekId, fresh.bytes, bind(owner), version)
        ).execute()
      stale.size

  private object Store:
    private val accounts = Store(
      "account_keys",
      "account_id",
      text => KeyOwner.Account(UUID.fromString(text)),
      {
        case KeyOwner.Account(id) => id
        case other                => throw IllegalArgumentException(s"not an account owner: $other")
      }
    )
    private val services = Store(
      "service_keys",
      "purpose",
      KeyOwner.Service(_),
      {
        case KeyOwner.Service(purpose) => purpose
        case other                     => throw IllegalArgumentException(s"not a service owner: $other")
      }
    )

    val all: List[Store] = List(accounts, services)

    def of(owner: KeyOwner): Store = owner match
      case KeyOwner.Account(_) => accounts
      case KeyOwner.Service(_) => services
