package dosecord.app

import dosecord.infra.Settings

import java.nio.file.Files
import java.nio.file.Path

/** Writes the generated `.env.example` (DESIGN.md section 12): `./mill app.test.runMain dosecord.app.envExampleGenerate
  * .env.example`.
  */
@main def envExampleGenerate(path: String): Unit =
  val target = Path.of(path)
  Option(target.getParent).foreach(Files.createDirectories(_))
  Files.writeString(target, Settings.envExample)
  println(s"wrote generated .env.example to $path")

/** Diff-check: exits 1 when the committed `.env.example` differs from the generated one (ROADMAP M0.5 acceptance;
  * wired into CI).
  */
@main def envExampleCheck(path: String): Unit =
  val committed = Files.readString(Path.of(path))
  if committed == Settings.envExample then println(s"$path matches the generated .env.example")
  else
    Console.err.println(s"$path is out of date; regenerate with:")
    Console.err.println(s"  ./mill app.test.runMain dosecord.app.envExampleGenerate $path")
    sys.exit(1)
