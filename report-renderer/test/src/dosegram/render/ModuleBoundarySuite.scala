package dosegram.render

import dosegram.render.text.PinnedFonts

import java.nio.file.Files

import scala.jdk.CollectionConverters.*

/** DEP-03 (ROADMAP G1.9): the pure module touches no file, no network and no AWT. Only `report-renderer.resvg` may
  * start a process.
  */
class ModuleBoundarySuite extends munit.FunSuite:
  private val Forbidden = Vector("java.io", "java.nio.file", "java.net", "java.awt", "javax.imageio", "scala.io", "scala.sys.process")

  test("the main sources of report-renderer import no file, network, process or AWT package"):
    val sources = PinnedFonts.dir.getParent.resolve("src")
    val files = Files.walk(sources).iterator.asScala.filter(_.toString.endsWith(".scala")).toVector
    assert(files.size > 20, s"only ${files.size} sources found under $sources")
    for
      file <- files
      line <- Files.readAllLines(file).asScala
      forbidden <- Forbidden
    do assert(!line.contains(forbidden), s"$file uses $forbidden: $line")
