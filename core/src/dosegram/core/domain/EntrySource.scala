package dosegram.core.domain

/** Where a tracking entry was created: the `source` entry column of ARCHITECTURE section 10.1. The `dbValue` strings
  * match the CHECK constraint of every entry table.
  */
enum EntrySource(val dbValue: String):
  case Bot extends EntrySource("bot")
  case MiniApp extends EntrySource("miniapp")
  case Import extends EntrySource("import")
  case Integration extends EntrySource("integration")
  case System extends EntrySource("system")

object EntrySource:
  def fromDbValue(value: String): EntrySource =
    values.find(_.dbValue == value).getOrElse(throw new IllegalArgumentException(s"unknown entry source '$value'"))
