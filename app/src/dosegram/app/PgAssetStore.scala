package dosegram.app

import dosegram.core.ports.Clock
import dosegram.infra.db.PgRenderAssets

/** The asset store over `render_assets` (ROADMAP G1.10): an image a flow drew is there for the adapter that uploads it,
  * in this process or in another one, for at most one hour.
  */
final class PgAssetStore(assets: PgRenderAssets, clock: Clock) extends AssetStore:
  override def put(assetKey: String, png: Array[Byte]): Unit = assets.put(assetKey, png, clock.now())
  override def holds(assetKey: String): Boolean = assets.holds(assetKey, clock.now())
  override def load(assetKey: String): Option[Array[Byte]] = assets.load(assetKey, clock.now())
