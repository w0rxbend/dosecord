package dosegram.infra.db

import dosegram.contracts.MediaKind
import dosegram.core.application.capture.ImportChat
import dosegram.core.application.imports.ImportBatch
import dosegram.core.application.imports.ImportService
import dosegram.core.domain.imports.CsvTemplate
import dosegram.core.domain.imports.ImportSource
import dosegram.core.ports.ConsentPurpose
import dosegram.core.ports.DataDomain
import dosegram.core.ports.IdSource
import dosegram.infra.FilePurgeList

import java.nio.file.Files
import java.util.random.RandomGenerator

/** ROADMAP G10.5 and BOT_UX section 11.1 rule 7 on Testcontainers Postgres 18, from the production code path: `/import`
  * asks the two-stage `import` consent, then offers the app and the templates; a template arrives as a document with a
  * header row only; a file sent to the chat is not opened, and the answer says why, the 20 MB limit included. The
  * consent given in the chat is the one the import service checks.
  */
class ImportChatPgSuite extends PgSuite:

  private def withRig(user: String)(test: ProductChatRig => Unit): Unit =
    val purge = FilePurgeList(Files.createTempFile("purge-list", ".txt"))
    ox.supervised(test(ProductChatRig(dataSource, MutableClock(ProductChatRig.Start), purge, user)))

  test("/import asks the import consent, then offers the app and the four templates"):
    withRig("import-1"): rig =>
      val account = rig.onboard()
      val asked = rig.line("/import")
      assert(asked.exists(_.contains("I agree that Dosegram reads the files I choose to import")), asked.toString)
      assert(!rig.uow.transaction(_.consents.isGranted(account, ConsentPurpose.Import)))
      rig.tap("I agree that", "I agree")
      val card = rig.tap("Please confirm", "Confirm")
      assert(card.exists(_.contains("Bring records in from a file")), card.toString)
      assert(rig.uow.transaction(_.consents.isGranted(account, ConsentPurpose.Import)))
      val (_, shown) = rig.adapter.sent.reverse.find((_, m) => m.chunks.exists(_.contains("Bring records in"))).get
      assertEquals(shown.choiceMap.map(_.label), List("Water template", "Mood template", "Sleep template", "Meals template"))
      assert(shown.controls.toString.contains("Import in app"), "the app button opens the import screen")
      // A second /import goes straight to the card.
      assert(rig.line("/import").exists(_.contains("Bring records in from a file")))

  test("a template tap sends the template as a document, its caption naming the columns"):
    withRig("import-2"): rig =>
      rig.onboard()
      rig.line("/import")
      rig.consent()
      rig.tap("Bring records in", "Sleep template")
      val (_, document) = rig.adapter.sent.reverse.find((_, m) => m.attachment.isDefined).get
      val attachment = document.attachment.get
      assertEquals(attachment.kind, MediaKind.Document)
      assertEquals(attachment.fileName, Some("dosegram-template-sleep.csv"))
      assertEquals(attachment.mimeType, CsvTemplate.Mime)
      assertEquals(ImportChat.templateOf(attachment.assetKey), Some(CsvTemplate.Sleep))
      assert(attachment.altText.contains("onset_at and wake_at"), attachment.altText)

  test("a file sent to the chat is not opened: the answer points to the app and states the 20 MB limit"):
    withRig("import-3"): rig =>
      rig.onboard()
      val answer = rig.document()
      assert(answer.exists(_.contains("Files are imported in the app. This file was not opened.")), answer.toString)
      assert(answer.exists(_.contains("larger than 20 MB")), answer.toString)
      val (_, shown) = rig.adapter.sent.reverse.find((_, m) => m.chunks.exists(_.contains("not opened"))).get
      assert(shown.controls.toString.contains("Import in app"))

  test("the consent given in the chat lets the import service start, and only for that account"):
    withRig("import-4"): rig =>
      val account = rig.onboard()
      val imports = ImportService(rig.clock, IdSource.uuidV7(RandomGenerator.getDefault))
      assert(rig.uow.transaction(imports.start(_, account, ImportSource.DosegramCsv)).isLeft)
      rig.line("/import")
      rig.consent()
      val job = rig.uow.transaction(imports.start(_, account, ImportSource.DosegramCsv)).toOption.get
      val day = ProductChatRig.Start.minusSeconds(3600).toString
      val preview = rig.uow
        .transaction(imports.addBatch(_, account, job.id, ImportBatch.CsvRows(s"occurred_at,amount_ml\n$day,250\n", 2)))
        .toOption
        .get
      assertEquals(preview.counts.tally(DataDomain.Hydration).fresh, 1)
end ImportChatPgSuite
