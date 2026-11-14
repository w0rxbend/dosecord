package dosegram.core.domain.imports

import dosegram.core.domain.mood.Energy
import dosegram.core.domain.mood.Valence
import dosegram.core.domain.nutrition.MealText
import dosegram.core.domain.nutrition.MealType
import dosegram.core.domain.sleep.EdgeSource
import dosegram.core.domain.sleep.SleepKind
import dosegram.core.domain.sleep.SleepTier
import dosegram.core.ports.DataDomain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** ROADMAP G10.5, PRODUCT_SPEC R-IMX-12 and R-IMX-13: the templates are the export's columns, an exported row reads back
  * as the same record, a time without an offset is read in the account's zone and flagged, a refused row names its
  * line, column and a code and never its value, and the CSV reader follows RFC 4180.
  */
class DosegramCsvSuite extends munit.FunSuite:

  private val kyiv = ZoneId.of("Europe/Kyiv")
  private val now = Instant.parse("2026-11-14T12:00:00Z")

  private def parsed(text: String, firstLine: Int = 2): DosegramCsv.Parsed =
    DosegramCsv.read(text, kyiv, now, firstLine).fold(r => fail(s"refused: $r"), identity)

  private def records(text: String): List[ImportRecord] =
    parsed(text).outcomes.collect { case RowOutcome.Parsed(_, record, _) => record }

  test("each template is the header of its domain's export columns, so it imports nothing unchanged"):
    assertEquals(CsvTemplate.Water.content, "occurred_at,amount_ml\r\n")
    assertEquals(CsvTemplate.Sleep.fileName, "dosegram-template-sleep.csv")
    CsvTemplate.values.foreach: template =>
      val read = parsed(template.content)
      assertEquals(read.template, template)
      assertEquals(read.outcomes, Nil)
    assertEquals(CsvTemplate.ofKind("doses"), None, "an import creates no dose action (R-IMX-11)")

  test("an exported water row, with every column of the table, reads back as the same drink"):
    val exported =
      "id,account_id,occurred_at,local_date,tz,source,client_key,import_job_id,external_ref_hmac,supersedes_id," +
        "amount_ml,entered_value,entered_unit,beverage_code,created_at,voided_at,void_reason\r\n" +
        "0190-a,acc,2026-11-10T08:30:00.123456+02:00,2026-11-10,Europe/Kyiv,bot,k,,,,250,250,ml,0," +
        "2026-11-10T08:30:01+02:00,,\r\n" +
        "0190-b,acc,2026-11-10T09:30:00+02:00,2026-11-10,Europe/Kyiv,bot,k2,,,,300,300,ml,0," +
        "2026-11-10T09:30:01+02:00,2026-11-10T09:31:00+02:00,undone\r\n"
    val read = parsed(exported)
    assertEquals(read.template, CsvTemplate.Water)
    assertEquals(
      read.outcomes,
      List(
        RowOutcome.Parsed(2, ImportRecord.Water(Instant.parse("2026-11-10T06:30:00.123456Z"), kyiv, 250), false),
        RowOutcome.Skipped(3)
      )
    )

  test("a typed time without an offset is read in the row's zone or the account's, and flagged (R-IMX-12)"):
    val read = parsed("occurred_at,amount_ml,tz\n2026-11-10 08:30,250,\n2026-11-10T08:30,250,UTC\n")
    assertEquals(
      read.outcomes,
      List(
        RowOutcome.Parsed(2, ImportRecord.Water(Instant.parse("2026-11-10T06:30:00Z"), kyiv, 250), true),
        RowOutcome.Parsed(3, ImportRecord.Water(Instant.parse("2026-11-10T08:30:00Z"), ZoneId.of("UTC"), 250), true)
      )
    )

  test("mood, sleep and meals read their columns; a night keeps its tier and edge sources"):
    val mood = records("occurred_at,valence,energy\n2026-11-10T20:00:00Z,4,\n2026-11-10T21:00:00Z,2,5\n")
    assertEquals(
      mood,
      List(
        ImportRecord.Mood(Instant.parse("2026-11-10T20:00:00Z"), kyiv, Valence.of(4).get, None),
        ImportRecord.Mood(Instant.parse("2026-11-10T21:00:00Z"), kyiv, Valence.of(2).get, Energy.of(5))
      )
    )
    val sleep = records(
      "onset_at,wake_at,kind,quality,tier,onset_source,wake_source\n" +
        "2026-11-09T21:30:00Z,2026-11-10T05:10:00Z,main,4,,,\n" +
        "2026-11-10T12:00:00Z,2026-11-10T12:40:00Z,nap,,3,sensor,sensor\n"
    )
    sleep match
      case List(night: ImportRecord.Sleep, nap: ImportRecord.Sleep) =>
        assertEquals(night.kind, SleepKind.Main)
        assertEquals(night.tier, SleepTier.Explicit)
        assertEquals(night.interval.onset.source, EdgeSource.Retrospective)
        assertEquals(night.quality.map(_.step), Some(4))
        assertEquals(night.localDate, LocalDate.parse("2026-11-10"))
        assertEquals(nap.tier, SleepTier.Sensor)
        assertEquals(nap.interval.wake.source, EdgeSource.Sensor)
      case other => fail(s"two nights expected, got $other")
    val meals = records("occurred_at,meal_type,description\n2026-11-10T10:00:00Z,Lunch,\"soup, bread\"\n")
    assertEquals(
      meals,
      List(ImportRecord.Meal(Instant.parse("2026-11-10T10:00:00Z"), kyiv, MealType.Lunch, MealText.of("soup, bread").toOption.flatten))
    )
    assertEquals(meals.map(_.domain), List(DataDomain.Nutrition))

  test("a refused row names its file line, column and code, never the value"):
    val text = "occurred_at,amount_ml\n2026-11-10T08:00:00Z,9000\nyesterday,250\n2026-11-10T08:00:00Z,\n" +
      "2026-11-20T08:00:00Z,250\n2026-11-10T08:00:00Z,2.5\n"
    val outcomes = parsed(text, firstLine = 102).outcomes
    assertEquals(
      outcomes,
      List(
        RowOutcome.Refused(102, "amount_ml", ImportProblem.OutOfRange),
        RowOutcome.Refused(103, "occurred_at", ImportProblem.InvalidTime),
        RowOutcome.Refused(104, "amount_ml", ImportProblem.MissingField),
        RowOutcome.Refused(105, "amount_ml", ImportProblem.InFuture),
        RowOutcome.Refused(106, "amount_ml", ImportProblem.InvalidNumber)
      )
    )
    val sleep = parsed("onset_at,wake_at,kind\n2026-11-10T05:00:00Z,2026-11-10T04:00:00Z,main\n" +
      "2026-11-10T01:00:00Z,2026-11-10T05:00:00Z,siesta\n").outcomes
    assertEquals(
      sleep,
      List(
        RowOutcome.Refused(2, "wake_at", ImportProblem.WakeNotAfterOnset),
        RowOutcome.Refused(3, "kind", ImportProblem.InvalidKind)
      )
    )

  test("a file whose header matches no template, or with an open quote, is refused as a whole"):
    assertEquals(DosegramCsv.read("name,dose\nx,1\n", kyiv, now), Left(CsvRefusal.UnknownColumns))
    assertEquals(DosegramCsv.read("", kyiv, now), Left(CsvRefusal.UnknownColumns))
    assertEquals(DosegramCsv.read("occurred_at,meal_type,description\nx,lunch,\"open", kyiv, now), Left(CsvRefusal.Unreadable))
    val tooMany = "occurred_at,amount_ml\n" + ("2026-11-10T08:00:00Z,250\n" * (ImportBounds.MaxBatchRecords + 1))
    assertEquals(DosegramCsv.read(tooMany, kyiv, now), Left(CsvRefusal.TooManyRows))

  test("RFC 4180: quotes, doubled quotes, line breaks in a field, CRLF, a byte order mark and semicolons"):
    val rows = CsvReader.rows("﻿a,b\r\n\"x, \"\"y\"\"\",\"two\nlines\"\r\n\r\n,\n3,4").get
    assertEquals(
      rows,
      List(
        CsvRow(1, Vector("a", "b")),
        CsvRow(2, Vector("x, \"y\"", "two\nlines")),
        CsvRow(6, Vector("3", "4"))
      )
    )
    assertEquals(CsvReader.rows("a;b\n1,5;2\n").get.map(_.fields), List(Vector("a", "b"), Vector("1,5", "2")))
    assertEquals(CsvReader.rows("a,\"b"), None)

  test("the reader stops at a field, a row or a text past its caps, quoted or not, and keeps what fits"):
    val small = CsvLimits(totalChars = 64, rowChars = 12, rowFields = 3, fieldChars = 5)
    assertEquals(CsvReader.rows("a,b,c\n12345,x\n", small).map(_.size), Some(2), "at the caps")
    assertEquals(CsvReader.rows("a,b\n123456\n", small), None, "a field past its cap")
    assertEquals(CsvReader.rows("a,b\n\"12\n3456\"\n", small), None, "a quoted field past its cap")
    assertEquals(CsvReader.rows("a,b,c,d\n", small), None, "a row with too many fields")
    assertEquals(CsvReader.rows("abcd,efgh,ijklm\n", small), None, "a row past its characters")
    assertEquals(CsvReader.rows("a\n" * 33, small), None, "a text past its cap")
    // One unclosed quote over a large file stops at the field cap, not at the end of the file.
    val endless = "occurred_at,meal_type,description\nx,lunch,\"" + ("y" * (CsvLimits.Default.fieldChars + 1))
    assertEquals(DosegramCsv.read(endless, kyiv, now), Left(CsvRefusal.Unreadable))
    val huge = "occurred_at,amount_ml\n" + ("x" * CsvLimits.Default.totalChars)
    assertEquals(DosegramCsv.read(huge, kyiv, now), Left(CsvRefusal.Unreadable))

  test("a staged record and the counts read back from their JSON"):
    val all = records("occurred_at,valence,energy\n2026-11-10T20:00:00Z,4,2\n") ++
      records("onset_at,wake_at,kind,quality\n2026-11-09T21:30:00Z,2026-11-10T05:10:00Z,main,3\n") ++
      records("occurred_at,meal_type,description\n2026-11-10T10:00:00Z,snack,apple\n") ++
      records("occurred_at,amount_ml\n2026-11-10T08:00:00Z,250\n")
    assertEquals(all.size, 4)
    all.foreach(record => assertEquals(RecordJson.read(RecordJson.write(record)), record))
    val counts = ImportCounts(
      domains = Map(DataDomain.Hydration -> DomainTally(3, 1, 0, 2)),
      invalid = 2,
      skipped = 1,
      ignored = 7,
      withoutConsent = 1,
      noOffset = 4,
      errors = List(RowError(5, "amount_ml", "out_of_range")),
      from = Some(LocalDate.parse("2026-11-01")),
      to = Some(LocalDate.parse("2026-11-10"))
    )
    assertEquals(ImportCounts.read(ImportCounts.write(counts)), counts)
    assertEquals((counts + counts).tally(DataDomain.Hydration), DomainTally(6, 2, 0, 4))
    assertEquals(ImportCounts.read("{}"), ImportCounts())

  test("an identity is the same for a record and for the entry it would be: domain, times and value"):
    val water = ImportRecord.Water(Instant.parse("2026-11-10T08:00:00.1234567Z"), kyiv, 250)
    assertEquals(water.identity, RecordIdentity.water(Instant.parse("2026-11-10T08:00:00.123456Z"), 250))
    assertNotEquals(water.identity, RecordIdentity.water(Instant.parse("2026-11-10T08:00:00.123456Z"), 251))
