package dosegram.app

import dosegram.adapter.telegram.FileIdCache
import dosegram.contracts.MediaKind
import dosegram.core.ports.Clock
import dosegram.infra.db.PgTelegramFileCache

/** The `file_id` cache over `telegram_file_cache` (ARCHITECTURE TG-35, TG-36): an image uploaded once is sent by its
  * file id after a restart as well.
  */
final class PgFileIdCache(cache: PgTelegramFileCache, clock: Clock) extends FileIdCache:
  override def get(assetKey: String, kind: MediaKind): Option[String] =
    cache.get(assetKey, PgFileIdCache.column(kind), clock.now())

  override def put(assetKey: String, kind: MediaKind, fileId: String): Unit =
    cache.put(assetKey, PgFileIdCache.column(kind), fileId, clock.now())

  override def remove(assetKey: String, kind: MediaKind): Unit = cache.remove(assetKey, PgFileIdCache.column(kind))

object PgFileIdCache:

  /** The `media_kind` value of the table. */
  private def column(kind: MediaKind): String =
    kind match
      case MediaKind.Photo    => "photo"
      case MediaKind.Document => "document"
