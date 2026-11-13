package dosegram.render.resvg

import dosegram.render.text.PinnedFonts

import java.nio.file.Files
import java.nio.file.Path

/** Finds the resvg binary for tests and tools: `DOSEGRAM_RESVG` when set, else the first `resvg` on the PATH. The
  * pixel tests are skipped, not failed, on a machine without it; the SVG goldens need no binary. CI sets
  * `DOSEGRAM_REQUIRE_RESVG=true`, and there a missing binary or another version fails them instead.
  */
object ResvgBinary:
  val PinnedVersion: String = "0.49.0"

  /** Whether this run must draw: a skipped pixel test would otherwise pass unseen. */
  def isRequired: Boolean = sys.env.get("DOSEGRAM_REQUIRE_RESVG").contains("true")

  def locate: Option[Path] =
    sys.env
      .get("DOSEGRAM_RESVG")
      .map(Path.of(_))
      .orElse(sys.env.getOrElse("PATH", "").split(':').iterator.filter(_.nonEmpty).map(Path.of(_, "resvg")).find(Files.isExecutable))

  /** The standard configuration against the pinned font pack, with an empty resources directory. The per-render
    * deadline is a minute, not the production five seconds: a loaded build host must not fail a pixel test. The
    * timeout itself is tested with fake binaries.
    */
  def config(binary: Path): ResvgConfig =
    ResvgConfig
      .standard(binary, PinnedFonts.dir, Files.createTempDirectory("dosegram-resvg-empty"))
      .copy(timeoutMillis = 60000)
