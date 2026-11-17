package dosegram.infra.db

import dosegram.infra.crypto.DataKeys

import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `telegram_file_cache` (ARCHITECTURE TG-35, TG-36): the file id of an uploaded asset, per bot and media kind. A file
  * id is valid only for the bot that received it, so the bot is part of the key. With the bot token a file id fetches
  * the user's card or archive, so it is sealed with the render service key (V22, ARCHITECTURE SEC-04); a plaintext
  * value the previous release left is read until the start-up step seals it.
  */
final class PgTelegramFileCache(dataSource: DataSource, botId: Long, keys: DataKeys):
  import SealedColumns.FileId

  private val pooled = Pooled(dataSource)

  /** The cached file id; a hit also moves `last_used_at`, which is what the pruning job reads. */
  def get(assetKey: String, mediaKind: String, now: Instant): Option[String] =
    pooled: conn ?=>
      given RowMapper[Option[String]] = rs =>
        Option(rs.getBytes("file_id_enc"))
          .map(keys.on(conn).openText(FileId.forService(assetKey), _))
          .orElse(Option(rs.getString("file_id")))
      sql"""UPDATE telegram_file_cache SET last_used_at = $now
            WHERE asset_key = $assetKey AND bot_id = $botId AND media_kind = $mediaKind
            RETURNING file_id, file_id_enc""".queryOne[Option[String]]().flatten

  def put(assetKey: String, mediaKind: String, fileId: String, now: Instant): Unit =
    pooled: conn ?=>
      val sealedId = keys.on(conn).sealText(FileId.forService(assetKey), fileId)
      sql"""INSERT INTO telegram_file_cache (asset_key, bot_id, media_kind, file_id_enc, created_at, last_used_at)
            VALUES ($assetKey, $botId, $mediaKind, $sealedId, $now, $now)
            ON CONFLICT (asset_key, bot_id, media_kind) DO UPDATE
              SET file_id = NULL, file_id_enc = EXCLUDED.file_id_enc, last_used_at = EXCLUDED.last_used_at""".execute()
    ()

  def remove(assetKey: String, mediaKind: String): Unit =
    pooled:
      sql"""DELETE FROM telegram_file_cache
            WHERE asset_key = $assetKey AND bot_id = $botId AND media_kind = $mediaKind""".execute()
    ()

  /** The pruning job: forgets the file ids of every bot that were last used before `cutoff`. */
  def forgetUnusedSince(cutoff: Instant): Int =
    pooled(sql"DELETE FROM telegram_file_cache WHERE last_used_at < $cutoff".execute())
