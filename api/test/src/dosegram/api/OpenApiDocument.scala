package dosegram.api

import io.circe.Printer
import io.circe.syntax.*
import sttp.apispec.openapi.OpenAPI
import sttp.apispec.openapi.circe.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter
import sttp.tapir.docs.openapi.OpenAPIDocsOptions

import java.nio.file.Files
import java.nio.file.Path

/** The OpenAPI document of `/api/v1`, generated from the Tapir definitions (ARCHITECTURE section 9.1). The committed
  * copy is `api/openapi.json`; the Mini App generates its types from that file (DEP-09).
  */
object OpenApiDocument:
  val Title = "Dosegram Mini App API"
  val Version = "1"

  // Tapir would document its own plain-text 400 for an undecodable request; the server answers with the problem
  // document instead (ApiModule.decodeFailureHandler), which every operation already lists.
  private val options = OpenAPIDocsOptions.default.copy(defaultDecodeFailureOutput = _ => None)

  def model: OpenAPI = OpenAPIDocsInterpreter(options).toOpenAPI(Endpoints.documented, Title, Version)

  def json: String = Printer.spaces2.copy(dropNullValues = true).print(model.asJson) + "\n"

/** Writes the document: `./mill api.test.runMain dosegram.api.openApiGenerate api/openapi.json`. */
@main def openApiGenerate(path: String): Unit =
  Files.writeString(Path.of(path), OpenApiDocument.json)
  println(s"wrote the generated OpenAPI document to $path")

/** Diff-check: exits 1 when the committed document differs from the generated one (ROADMAP G3.1; wired into CI). */
@main def openApiCheck(path: String): Unit =
  if Files.readString(Path.of(path)) == OpenApiDocument.json then println(s"$path matches the Tapir endpoints")
  else
    Console.err.println(s"$path is out of date; regenerate with:")
    Console.err.println(s"  ./mill api.test.runMain dosegram.api.openApiGenerate $path")
    Console.err.println("then regenerate the Mini App types: pnpm --dir miniapp run api:generate")
    sys.exit(1)
