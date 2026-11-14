package dosegram.core.domain.imports

import dosegram.core.ports.DataDomain

import java.time.LocalDate

/** The records of one domain in an import: new ones, duplicates of a record the account holds or the import already has
  * (R-IMX-9), conflicts (a night that overlaps another), and, after the commit, the entries written.
  */
final case class DomainTally(fresh: Int = 0, duplicate: Int = 0, conflict: Int = 0, imported: Int = 0):
  def +(other: DomainTally): DomainTally =
    DomainTally(fresh + other.fresh, duplicate + other.duplicate, conflict + other.conflict, imported + other.imported)
  def staged: Int = fresh + duplicate + conflict

/** A refused row: the line of the file, the column and a code. Never the value (ARCHITECTURE API-14). */
final case class RowError(line: Int, field: String, code: String)

/** The preview of an import (PRODUCT_SPEC R-IMX-8): record counts per domain, the date range, and the rows that are not
  * imported: invalid (with their first error lines), skipped (voided entries of an exported file), ignored (types the
  * user did not select, counted on the device), and those of a domain without its consent. `noOffset` counts the
  * records whose time had no offset and was read in the account's zone (R-IMX-12).
  */
final case class ImportCounts(
    domains: Map[DataDomain, DomainTally] = Map.empty,
    invalid: Int = 0,
    skipped: Int = 0,
    ignored: Int = 0,
    withoutConsent: Int = 0,
    noOffset: Int = 0,
    errors: List[RowError] = Nil,
    from: Option[LocalDate] = None,
    to: Option[LocalDate] = None
):
  def staged: Int = domains.values.map(_.staged).sum
  def tally(domain: DataDomain): DomainTally = domains.getOrElse(domain, DomainTally())

  def +(other: ImportCounts): ImportCounts =
    ImportCounts(
      domains = (domains.keySet ++ other.domains.keySet).map(d => d -> (tally(d) + other.tally(d))).toMap,
      invalid = invalid + other.invalid,
      skipped = skipped + other.skipped,
      ignored = ignored + other.ignored,
      withoutConsent = withoutConsent + other.withoutConsent,
      noOffset = noOffset + other.noOffset,
      errors = (errors ++ other.errors).take(ImportBounds.MaxListedErrors),
      from = (from ++ other.from).minOption,
      to = (to ++ other.to).maxOption
    )

object ImportCounts:

  def write(counts: ImportCounts): String =
    val json = ujson.Obj(
      "domains" -> ujson.Obj.from(
        counts.domains.toList
          .sortBy(_._1.ordinal)
          .map: (domain, tally) =>
            domain.code -> ujson.Obj(
              "new" -> tally.fresh,
              "duplicate" -> tally.duplicate,
              "conflict" -> tally.conflict,
              "imported" -> tally.imported
            )
      ),
      "invalid" -> counts.invalid,
      "skipped" -> counts.skipped,
      "ignored" -> counts.ignored,
      "withoutConsent" -> counts.withoutConsent,
      "noOffset" -> counts.noOffset,
      "errors" -> ujson.Arr.from(
        counts.errors.map(e => ujson.Obj("line" -> e.line, "field" -> e.field, "code" -> e.code))
      )
    )
    counts.from.foreach(date => json("from") = date.toString)
    counts.to.foreach(date => json("to") = date.toString)
    ujson.write(json)

  def read(text: String): ImportCounts =
    val json = ujson.read(text).obj
    def int(key: String): Int = json.get(key).flatMap(_.numOpt).fold(0)(_.toInt)
    def date(key: String): Option[LocalDate] = json.get(key).flatMap(_.strOpt).map(LocalDate.parse)
    val domains = json
      .get("domains")
      .flatMap(_.objOpt)
      .fold(Map.empty[DataDomain, DomainTally]): entries =>
        entries.toList
          .flatMap: (code, tally) =>
            def n(key: String): Int = tally.obj.get(key).flatMap(_.numOpt).fold(0)(_.toInt)
            DataDomain.ofCode(code).map(_ -> DomainTally(n("new"), n("duplicate"), n("conflict"), n("imported")))
          .toMap
    val errors = json
      .get("errors")
      .flatMap(_.arrOpt)
      .fold(Nil): items =>
        items.toList.map(e => RowError(e("line").num.toInt, e("field").str, e("code").str))
    ImportCounts(
      domains,
      int("invalid"),
      int("skipped"),
      int("ignored"),
      int("withoutConsent"),
      int("noOffset"),
      errors,
      date("from"),
      date("to")
    )
end ImportCounts
