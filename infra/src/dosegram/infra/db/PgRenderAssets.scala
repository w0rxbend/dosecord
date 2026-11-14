package dosegram.infra.db

import dosegram.core.ports.KeyOwner
import dosegram.infra.crypto.DataKeys

import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `render_assets` (ARCHITECTURE section 10.11, ROADMAP G1.10): the bytes of a drawn image for at most one hour. An
  * image past its `purge_after` is not there, whether or not the deleting job has taken the row yet. A card carries the
  * user's own figures and words, so its bytes are sealed with the render service key (V10, ARCHITECTURE SEC-04): the
  * store has no account column.
  */
final class PgRenderAssets(dataSource: DataSource, keys: DataKeys):
  import SealedColumns.RenderBytes

  private val pooled = Pooled(dataSource)
  private val owner = KeyOwner.Service("render")

  /** Stores the image, or replaces the one under the key and restarts its hour. */
  def put(assetKey: String, png: Array[Byte], now: Instant): Unit =
    val purgeAfter = now.plus(PgRenderAssets.Lifetime)
    pooled: conn ?=>
      val sealedPng = keys.on(conn).seal(RenderBytes.field(owner, assetKey), png)
      sql"""INSERT INTO render_assets (asset_key, mime, byte_size, bytes_enc, created_at, purge_after)
            VALUES ($assetKey, 'image/png', ${png.length}, $sealedPng, $now, $purgeAfter)
            ON CONFLICT (asset_key) DO UPDATE
              SET byte_size = EXCLUDED.byte_size, bytes_enc = EXCLUDED.bytes_enc, created_at = EXCLUDED.created_at,
                  purge_after = EXCLUDED.purge_after""".execute()
    ()

  def load(assetKey: String, now: Instant): Option[Array[Byte]] =
    pooled: conn ?=>
      given RowMapper[Array[Byte]] = _.getBytes(1)
      sql"SELECT bytes_enc FROM render_assets WHERE asset_key = $assetKey AND purge_after > $now"
        .queryOne[Array[Byte]]()
        .map(keys.on(conn).open(RenderBytes.field(owner, assetKey), _))

  def holds(assetKey: String, now: Instant): Boolean =
    pooled:
      sql"SELECT 1 FROM render_assets WHERE asset_key = $assetKey AND purge_after > $now".queryOne[Int]().isDefined

  /** The deleting job: removes every image whose hour has passed and returns how many there were. */
  def purge(now: Instant): Int =
    pooled(sql"DELETE FROM render_assets WHERE purge_after <= $now".execute())

object PgRenderAssets:
  val Lifetime: Duration = Duration.ofHours(1)
