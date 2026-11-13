package dosegram.render.resvg

import dosegram.render.text.PinnedFonts

import java.nio.file.Files
import java.nio.file.Path

/** Finds the resvg binary for tests and tools: `DOSEGRAM_RESVG` when set, else the first `resvg` on the PATH. The
  * pixel tests are skipped, not failed, on a machine without it; the SVG goldens need no binary.
  */
object ResvgBinary:
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
