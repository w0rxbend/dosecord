package dosegram.app

import sttp.model.StatusCode
import sttp.shared.Identity
import sttp.tapir.*
import sttp.tapir.server.ServerEndpoint

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** The built Mini App, served under `/app/` on the API's own origin (ARCHITECTURE section 9.1, MINI_APP MA-HOST-1 to
  * MA-HOST-4). The files are read once at start-up into memory (the whole application is a few hundred kilobytes), so a
  * request can only name a file that was in the directory then: no request path ever reaches the file system.
  */
final class StaticApp private (files: Map[String, StaticApp.File]):
  import StaticApp.*

  private val shell: Option[File] = files.get(IndexFile)

  /** `GET /app` and everything below it. */
  val endpoint: ServerEndpoint[Any, Identity] =
    StaticApp.definition.handle(segments => answer(segments).toRight(StatusCode.NotFound))

  private def answer(segments: List[String]): Option[(String, String, Array[Byte])] =
    val path = segments.filter(_.nonEmpty).mkString("/")
    val found =
      if path.isEmpty || isRoute(segments) then shell.map(_ -> NoStore)
      else files.get(path).map(file => file -> cachingOf(path))
    found.map((file, caching) => (file.contentType, caching, file.bytes))

object StaticApp:
  private val IndexFile = "index.html"
  private val NoStore = "no-store"
  private val Immutable = "public, max-age=31536000, immutable"

  final case class File(contentType: String, bytes: Array[Byte])

  // Source maps are a build artefact and are not served (MINI_APP 13.1 step 6).
  private val ContentTypes = Map(
    "html" -> "text/html; charset=utf-8",
    "js" -> "text/javascript; charset=utf-8",
    "css" -> "text/css; charset=utf-8",
    "json" -> "application/json",
    "svg" -> "image/svg+xml",
    "png" -> "image/png",
    "ico" -> "image/x-icon",
    "woff2" -> "font/woff2",
    "webmanifest" -> "application/manifest+json",
    "txt" -> "text/plain; charset=utf-8"
  )

  private val definition =
    endpoint.get
      .in("app" / paths)
      .errorOut(statusCode)
      .out(header[String]("Content-Type"))
      .out(header[String]("Cache-Control"))
      .out(byteArrayBody)

  /** No application: every path under `/app/` answers 404. */
  val absent: StaticApp = StaticApp(Map.empty)

  /** Reads every servable file below `directory`. A directory without `index.html` is not an application. */
  def load(directory: Path): StaticApp =
    require(Files.isRegularFile(directory.resolve(IndexFile)), s"$directory holds no $IndexFile")
    val stream = Files.walk(directory)
    try
      val files =
        for
          path <- stream.iterator().asScala.toList if Files.isRegularFile(path)
          contentType <- ContentTypes.get(extensionOf(path.getFileName.toString))
        yield directory.relativize(path).iterator().asScala.mkString("/") -> File(contentType, Files.readAllBytes(path))
      StaticApp(files.toMap)
    finally stream.close()

  /** A path whose last segment has no extension is a route of the application's own router, answered with the shell
    * (MA-HOST-4, MA-ROUTE-3).
    */
  private def isRoute(segments: List[String]): Boolean = segments.lastOption.forall(!_.contains('.'))

  /** The shell is never stored, so a deploy is seen at the next launch; files under `assets/` carry a content hash in
    * their name and never change (section 9.1).
    */
  private def cachingOf(path: String): String =
    if path == IndexFile then NoStore else if path.startsWith("assets/") then Immutable else "no-cache"

  private def extensionOf(name: String): String = name.drop(name.lastIndexOf('.') + 1).toLowerCase
