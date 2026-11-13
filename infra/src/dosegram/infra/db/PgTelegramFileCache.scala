package dosegram.infra.db

import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `telegram_file_cache` (ARCHITECTURE TG-35, TG-36): the file id of an uploaded asset, per bot and media kind. A file
  * id is valid only for the bot that received it, so the bot is part of the key.
  */
final class PgTelegramFileCache(dataSource: DataSource, botId: Long):
  private val pooled = Pooled(dataSource)

  /** The cached file id; a hit also moves `last_used_at`, which is what the pruning job reads. */
  def get(assetKey: String, mediaKind: String, now: Instant): Option[String] =
    pooled:
      sql"""UPDATE telegram_file_cache SET last_used_at = $now
            WHERE asset_key = $assetKey AND bot_id = $botId AND media_kind = $mediaKind
            RETURNING file_id""".queryOne[String]()

  def put(assetKey: String, mediaKind: String, fileId: String, now: Instant): Unit =
    pooled:
      sql"""INSERT INTO telegram_file_cache (asset_key, bot_id, media_kind, file_id, created_at, last_used_at)
            VALUES ($assetKey, $botId, $mediaKind, $fileId, $now, $now)
            ON CONFLICT (asset_key, bot_id, media_kind) DO UPDATE
              SET file_id = EXCLUDED.file_id, last_used_at = EXCLUDED.last_used_at""".execute()
    ()

  def remove(assetKey: String, mediaKind: String): Unit =
    pooled:
      sql"""DELETE FROM telegram_file_cache
            WHERE asset_key = $assetKey AND bot_id = $botId AND media_kind = $mediaKind""".execute()
    ()

  /** The pruning job: forgets the file ids of every bot that were last used before `cutoff`. */
  def forgetUnusedSince(cutoff: Instant): Int =
    pooled(sql"DELETE FROM telegram_file_cache WHERE last_used_at < $cutoff".execute())
