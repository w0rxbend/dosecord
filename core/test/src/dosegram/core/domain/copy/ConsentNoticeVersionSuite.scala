package dosegram.core.domain.copy

import dosegram.core.ports.ConsentPurpose

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The rule of [[ConsentCopy]]: a change of a statement is a new notice version (finding 4 of the verification of
  * 2026-11-27: the Ukrainian import statement changed on 2026-11-15 while the version stayed 1). The text of both
  * stages of every purpose, in English and Ukrainian, is pinned by its SHA-256 under the version that names it, so a
  * changed statement fails here until [[ConsentCopy.NoticeVersion]] moves and the new text is pinned under it.
  */
class ConsentNoticeVersionSuite extends munit.FunSuite:

  /** Version 1 is the text before the Ukrainian review of 2026-11-15; version 2 is the text after it. */
  private val pinned: Map[Int, String] = Map(
    2 -> "742146b2956aa225bea0452823664934fcd025a396d244bce83af5e8e525e049"
  )

  private def bothStages: String =
    Lang.values.toList
      .flatMap: lang =>
        Lang.within(lang)(ConsentPurpose.values.toList.flatMap(p => List(ConsentCopy.statement(p), ConsentCopy.confirmation(p))))
      .mkString("\n")

  private def sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).map(b => f"$b%02x").mkString

  test("the consent text of both stages, in both languages, is the text its notice version pins"):
    assertEquals(Some(sha256(bothStages)), pinned.get(ConsentCopy.NoticeVersion))
