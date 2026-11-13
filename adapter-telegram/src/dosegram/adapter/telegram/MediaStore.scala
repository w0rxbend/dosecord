package dosegram.adapter.telegram

import dosegram.contracts.MediaKind

import java.util.concurrent.ConcurrentHashMap

/** Where the adapter gets the bytes behind an `AttachmentRef.assetKey` (ADR-015: the key is opaque and the asset store
  * owns the bytes). A port of this module: the render and media tracks own the store and implement it. `None` means the
  * asset is not there (it expired, or no store is wired yet); the adapter then sends the attachment's text equivalent.
  */
trait MediaStore:
  def load(assetKey: String): Option[Array[Byte]]

object MediaStore:
  /** No store wired: every attachment falls back to its text equivalent. */
  val empty: MediaStore = _ => None

/** The `file_id` cache of ARCHITECTURE TG-35 and TG-36, keyed by asset and media kind for the one bot of this process.
  */
trait FileIdCache:
  def get(assetKey: String, kind: MediaKind): Option[String]
  def put(assetKey: String, kind: MediaKind, fileId: String): Unit
  def remove(assetKey: String, kind: MediaKind): Unit

/** A cache for the life of the process. The durable table `telegram_file_cache` (ARCHITECTURE section 10.11) replaces
  * it when the schema has one; until then a restart costs one upload per asset.
  */
final class InMemoryFileIdCache extends FileIdCache:
  private val entries = ConcurrentHashMap[(String, MediaKind), String]()

  override def get(assetKey: String, kind: MediaKind): Option[String] = Option(entries.get((assetKey, kind)))

  override def put(assetKey: String, kind: MediaKind, fileId: String): Unit =
    entries.put((assetKey, kind), fileId)
    ()

  override def remove(assetKey: String, kind: MediaKind): Unit =
    entries.remove((assetKey, kind))
    ()
