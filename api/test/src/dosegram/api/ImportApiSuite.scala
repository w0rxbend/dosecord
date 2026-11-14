package dosegram.api

import dosegram.core.ports.ConsentPurpose
import dosegram.infra.db.sql

import java.sql.Connection
import java.util.UUID
import scala.language.implicitConversions

/** ROADMAP G10.5 and G10.7 through the routes (ARCHITECTURE section 9.5): a template is a header row and needs no
  * session; an import needs the `import` consent; a batch is read into a preview with counts and duplicates and stores
  * no entry; the commit writes the new records; the same file again adds nothing; DELETE undoes exactly the import; a
  * batch is at most 2 MB, larger than the 64 kB of the other routes; another account's import is not found.
  */
class ImportApiSuite extends ApiSuite:

  private val water = "occurred_at,amount_ml\n2026-11-12T08:00:00Z,250\n2026-11-12T09:00:00Z,300\n" +
    "2026-11-12T09:00:00Z,300\n2026-11-12T10:00:00Z,99999\n"

  private def importer(): (UUID, String) =
    val account = telegramAccount()
    fixtures.grant(account.accountId, ConsentPurpose.Import, ConsentPurpose.Hydration, ConsentPurpose.Sleep)
    (account.accountId, login(account))

  private def entries(account: UUID): Int =
    withConnection: conn =>
      given Connection = conn
      sql"SELECT count(*) FROM hydration_entries WHERE account_id = $account AND voided_at IS NULL"
        .queryOne[Int]()
        .getOrElse(0)

  private def start(token: String, source: String): String =
    val created = post("/api/v1/imports", ujson.Obj("source" -> source), token, newKey())
    assertEquals(created.status, 201, created.body)
    assertEquals(created.json("state").str, "open")
    created.json("id").str

  private def batch(token: String, id: String, body: ujson.Value): Reply =
    post(s"/api/v1/imports/$id/batches", body, token, newKey())

  test("a template is the header row of its domain, served without a session to the Telegram client"):
    val reply = get("/api/v1/import-templates/sleep")
    assertEquals(reply.status, 200)
    assertEquals(reply.body, "onset_at,wake_at,kind,quality\r\n")
    assertEquals(reply.header("Content-Disposition"), Some("attachment; filename=\"dosegram-template-sleep.csv\""))
    assertEquals(reply.header("Access-Control-Allow-Origin"), Some(Exports.AllowedOrigin))
    assertProblem(get("/api/v1/import-templates/doses"), 404, "not_found")

  test("without the import consent nothing starts: 403 consent_required naming the purpose"):
    val account = telegramAccount()
    val token = login(account)
    val refused = post("/api/v1/imports", ujson.Obj("source" -> "dosegram_csv"), token, newKey())
    assertProblem(refused, 403, "consent_required")
    assertEquals(refused.json("purpose").str, "import")

  test("preview, commit, the same file again, and undo, through the routes (AC-IMX-5, AC-IMX-6)"):
    val (account, token) = importer()
    val id = start(token, "dosegram_csv")
    val preview = batch(token, id, ujson.Obj("csv" -> water, "firstLine" -> 2))
    assertEquals(preview.status, 200, preview.body)
    val domain = preview.json("domains").arr.head
    assertEquals(domain("domain").str, "hydration")
    assertEquals((domain("fresh").num, domain("duplicates").num, domain("imported").num), (2.0, 1.0, 0.0))
    assertEquals(preview.json("invalid").num, 1.0)
    assertEquals(preview.json("errors").arr.map(e => (e("line").num, e("field").str, e("code").str)).toList,
      List((5.0, "amount_ml", "out_of_range")))
    assert(!preview.body.contains("99999"), "a preview never echoes a value of the file")
    assertEquals(entries(account), 0, "a preview stores no entry")

    val committed = post(s"/api/v1/imports/$id/commit", ujson.Obj(), token, newKey())
    assertEquals(committed.status, 200, committed.body)
    assertEquals(committed.json("state").str, "committed")
    assertEquals(entries(account), 2)
    assertProblem(batch(token, id, ujson.Obj("csv" -> water)), 412, "version_conflict")

    val again = start(token, "dosegram_csv")
    batch(token, again, ujson.Obj("csv" -> water))
    post(s"/api/v1/imports/$again/commit", ujson.Obj(), token, newKey())
    assertEquals(entries(account), 2, "the same file twice adds nothing")

    val undone = delete(s"/api/v1/imports/$id", token)
    assertEquals(undone.status, 200, undone.body)
    assertEquals(undone.json("state").str, "undone")
    assertEquals(entries(account), 0)
    assertEquals(get("/api/v1/imports", token).json("items").arr.size, 2)

  test("device records: a night and a nap as tier 3, a drink; a CSV batch to a device import is refused"):
    val (account, token) = importer()
    val id = start(token, "apple_health")
    val records = ujson.Arr(
      ujson.Obj("type" -> "water", "at" -> "2026-11-12T08:00:00Z", "amountMl" -> 200),
      ujson.Obj("type" -> "sleep_main", "onset" -> "2026-11-11T22:00:00Z", "wake" -> "2026-11-12T06:00:00Z"),
      ujson.Obj("type" -> "sleep_nap", "onset" -> "2026-11-12T13:00:00Z", "wake" -> "2026-11-12T13:30:00Z")
    )
    val preview = batch(token, id, ujson.Obj("records" -> records, "ignored" -> 12, "firstIndex" -> 1))
    assertEquals(preview.status, 200, preview.body)
    assertEquals(preview.json("ignored").num, 12.0)
    assertEquals(preview.json("domains").arr.map(d => d("domain").str -> d("fresh").num).toMap,
      Map("hydration" -> 1.0, "sleep" -> 2.0))
    assertProblem(batch(token, id, ujson.Obj("csv" -> water)), 422, "validation_failed")
    assertProblem(batch(token, id, ujson.Obj("records" -> ujson.Arr(ujson.Obj("type" -> "water")))), 422,
      "validation_failed")
    post(s"/api/v1/imports/$id/commit", ujson.Obj(), token, newKey())
    val tiers = withConnection: conn =>
      given Connection = conn
      sql"SELECT tier FROM sleep_episodes WHERE account_id = $account AND source = 'import'".query[Int]()
    assertEquals(tiers, List(3, 3))

  test("imported entries are marked as imported on the entry routes, and own entries are not (G10.5)"):
    val (_, token) = importer()
    val own = post("/api/v1/hydration/entries", ujson.Obj("amountMl" -> 150), token, newKey())
    assertEquals(own.status, 201, own.body)
    val id = start(token, "apple_health")
    val records = ujson.Arr(
      ujson.Obj("type" -> "water", "at" -> "2026-11-12T08:00:00Z", "amountMl" -> 200),
      ujson.Obj("type" -> "sleep_main", "onset" -> "2026-11-11T22:00:00Z", "wake" -> "2026-11-12T06:00:00Z")
    )
    assertEquals(batch(token, id, ujson.Obj("records" -> records)).status, 200)
    assertEquals(post(s"/api/v1/imports/$id/commit", ujson.Obj(), token, newKey()).status, 200)

    val water = get("/api/v1/hydration/entries?from=2026-11-01&to=2026-11-30", token).json("items").arr
    assertEquals(water.map(e => e("amountMl").num -> e("imported").bool).toSet, Set(200.0 -> true, 150.0 -> false))
    assert(water.find(_("imported").bool).exists(_("text").str.contains("imported")), water.toString)
    val night = get("/api/v1/sleep/episodes?from=2026-11-01&to=2026-11-30", token).json("items").arr.head
    assert(night("imported").bool)
    assertEquals(night("source").str, "Imported from a device export")

  test("a batch may be up to 2 MB, where every other route stops at 64 kB; beyond it is 413"):
    val (_, token) = importer()
    val id = start(token, "dosegram_csv")
    val rows = (0 until 4000).map(i => f"2026-11-1${i % 2}T${i % 24}%02d:${i % 60}%02d:00Z,${100 + i % 400}").mkString("\n")
    val large = ujson.Obj("csv" -> s"occurred_at,amount_ml\n$rows\n")
    assert(ujson.write(large).length > Endpoints.MaxBodyBytes)
    assertEquals(batch(token, id, large).status, 200)
    val huge = ujson.Obj("csv" -> ("x" * (Imports.MaxBatchBytes.toInt + 10)))
    assertProblem(batch(token, id, huge), 413, "payload_too_large")

  test("another account's import is not found, and cannot be committed or removed"):
    val (_, owner) = importer()
    val (_, other) = importer()
    val id = start(owner, "dosegram_csv")
    assertProblem(get(s"/api/v1/imports/$id", other), 404, "not_found")
    assertProblem(post(s"/api/v1/imports/$id/commit", ujson.Obj(), other, newKey()), 404, "not_found")
    assertProblem(delete(s"/api/v1/imports/$id", other), 404, "not_found")
    assertEquals(get(s"/api/v1/imports/$id", owner).json("state").str, "open")
