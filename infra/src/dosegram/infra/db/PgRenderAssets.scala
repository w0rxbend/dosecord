package dosegram.infra.db

import java.time.Duration
import java.time.Instant
import javax.sql.DataSource
import scala.language.implicitConversions

/** `render_assets` (ARCHITECTURE section 10.11, ROADMAP G1.10): the bytes of a drawn image for at most one hour. An
  * image past its `purge_after` is not there, whether or not the deleting job has taken the row yet.
  */
final class PgRenderAssets(dataSource: DataSource):
  private val pooled = Pooled(dataSource)

  /** Stores the image, or replaces the one under the key and restarts its hour. */
  def put(assetKey: String, png: Array[Byte], now: Instant): Unit =
    val purgeAfter = now.plus(PgRenderAssets.Lifetime)
    pooled:
      sql"""INSERT INTO render_assets (asset_key, mime, byte_size, bytes, created_at, purge_after)
            VALUES ($assetKey, 'image/png', ${png.length}, $png, $now, $purgeAfter)
            ON CONFLICT (asset_key) DO UPDATE
              SET byte_size = EXCLUDED.byte_size, bytes = EXCLUDED.bytes, created_at = EXCLUDED.created_at,
                  purge_after = EXCLUDED.purge_after""".execute()
    ()

  def load(assetKey: String, now: Instant): Option[Array[Byte]] =
    pooled:
      given RowMapper[Array[Byte]] = _.getBytes(1)
      sql"SELECT bytes FROM render_assets WHERE asset_key = $assetKey AND purge_after > $now".queryOne[Array[Byte]]()

  def holds(assetKey: String, now: Instant): Boolean =
    pooled:
      sql"SELECT 1 FROM render_assets WHERE asset_key = $assetKey AND purge_after > $now".queryOne[Int]().isDefined

  /** The deleting job: removes every image whose hour has passed and returns how many there were. */
  def purge(now: Instant): Int =
    pooled(sql"DELETE FROM render_assets WHERE purge_after <= $now".execute())

object PgRenderAssets:
  val Lifetime: Duration = Duration.ofHours(1)
