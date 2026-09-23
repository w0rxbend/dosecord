package dosecord.infra.db

import dosecord.core.ports.MedicationStatus

import java.time.Instant
import java.util.UUID

/** `MedicationRepository.searchByNameNormPrefix` (ROADMAP M2.1: slash-option autocomplete over `name_norm`) against
  * Testcontainers Postgres 18: prefix matching is case-normalised by the generated column, scoped to the account,
  * excludes archived rows, honours the limit, and treats LIKE wildcards literally.
  */
class MedicationSearchPgSuite extends PgSuite:

  private val t0 = Instant.parse("2026-09-21T00:00:00Z")

  override def beforeEach(context: BeforeEach): Unit =
    withConnection { conn =>
      val st = conn.createStatement()
      try st.execute("TRUNCATE medications, users CASCADE")
      finally st.close()
    }

  test("autocomplete prefix search is scoped, case-normalised, archived-excluded and bounded"):
    val fixtures = Fixtures(dataSource)
    val account = fixtures.account()
    val other = fixtures.account()
    fixtures.medication(account, "Vitamin D", t0)
    fixtures.medication(account, "Vitamin K2", t0)
    fixtures.medication(account, "Metformin", t0)
    val archived = fixtures.medication(account, "Vitamin Archived", t0)
    fixtures.medication(other, "Vitamin Elsewhere", t0)
    val uow = fixtures.uow
    uow.transaction(
      _.medications.insert(
        dosecord.core.ports.NewMedication(UUID.randomUUID(), account, "100% Pure", None, None, None),
        t0
      )
    )
    uow.transaction(
      _.medications.insert(
        dosecord.core.ports.NewMedication(UUID.randomUUID(), account, "1000 IU", None, None, None),
        t0
      )
    )
    withConnection { conn =>
      val st = conn.createStatement()
      try st.execute(s"UPDATE medications SET status = 'archived' WHERE id = '$archived'")
      finally st.close()
    }

    val found = uow.transaction(_.medications.searchByNameNormPrefix(account, "vit", 25))
    // name_norm ordering, archived excluded:
    assertEquals(found.map(_.name), List("Vitamin D", "Vitamin K2"))

    assertEquals(uow.transaction(_.medications.searchByNameNormPrefix(account, "vit", 1)).map(_.name), List("Vitamin D"))
    assertEquals(uow.transaction(_.medications.searchByNameNormPrefix(other, "vit", 25)).map(_.name), List("Vitamin Elsewhere"))
    assertEquals(uow.transaction(_.medications.searchByNameNormPrefix(account, "xyz", 25)), Nil)

    // LIKE wildcards stay literal: "100%" matches only a literal "100%" prefix, never "1000 IU".
    assertEquals(uow.transaction(_.medications.searchByNameNormPrefix(account, "100%", 25)).map(_.name), List("100% Pure"))
    assertEquals(
      uow.transaction(_.medications.searchByNameNormPrefix(account, "100", 25)).map(_.name),
      List("100% Pure", "1000 IU")
    )

  test("archived medications are excluded"):
    val fixtures = Fixtures(dataSource)
    val account = fixtures.account()
    val id = fixtures.medication(account, "Old Med", t0)
    val uow = fixtures.uow
    withConnection { conn =>
      val st = conn.createStatement()
      try st.execute(s"UPDATE medications SET status = '${MedicationStatus.Archived.dbValue}' WHERE id = '$id'")
      finally st.close()
    }
    assertEquals(uow.transaction(_.medications.searchByNameNormPrefix(account, "old", 25)), Nil)
