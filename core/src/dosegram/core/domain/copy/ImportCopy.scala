package dosegram.core.domain.copy

import dosegram.core.domain.imports.CsvTemplate

/** The import copy of the chat (BOT_UX section 11.1 rule 7, `in.file.import`; ROADMAP G10.5). Imports run in the app:
  * the chat offers the templates and opens the app, and never downloads a file the user sends (PRODUCT_SPEC R-IMX-7).
  */
object ImportCopy:

  def card: String = tr(
    "Bring records in from a file. The app reads a Dosegram CSV file or an Apple Health export on your device, " +
      "shows what it found, new records and duplicates, and stores nothing until you confirm. Every import can be " +
      "removed again in the app.",
    "Перенесіть записи з файлу. Застосунок читає на вашому пристрої файл CSV Dosegram або експорт Apple Health, " +
      "показує знайдене, нові записи й дублікати, і нічого не зберігає, доки ви не підтвердите. Кожен імпорт можна " +
      "вилучити в застосунку."
  )

  def templatesHint: String = tr(
    "To fill in a file yourself, take an empty template: one per kind of record, a header row only.",
    "Щоб заповнити файл самостійно, візьміть порожній шаблон: один на кожен вид записів, лише рядок заголовків."
  )

  def ImportInApp: String = tr("Import in app", "Імпорт у застосунку")

  def templateLabel(template: CsvTemplate): String =
    template match
      case CsvTemplate.Water => tr("Water template", "Шаблон: вода")
      case CsvTemplate.Mood  => tr("Mood template", "Шаблон: настрій")
      case CsvTemplate.Sleep => tr("Sleep template", "Шаблон: сон")
      case CsvTemplate.Meals => tr("Meals template", "Шаблон: їжа")

  /** The caption of a template file: its columns in words; it is its own text equivalent. */
  def templateCaption(template: CsvTemplate): String =
    template match
      case CsvTemplate.Water =>
        tr(
          "Water: one row per drink. occurred_at is the date and time, for example 2026-11-14 08:30; amount_ml is " +
            "the amount in millilitres.",
          "Вода: один рядок на кожен напій. occurred_at — дата й час, наприклад 2026-11-14 08:30; amount_ml — " +
            "кількість у мілілітрах."
        )
      case CsvTemplate.Mood =>
        tr(
          "Mood: one row per check-in. valence is a step from 1 to 5; energy, from 1 to 5, may stay empty.",
          "Настрій: один рядок на кожну позначку. valence — рівень від 1 до 5; energy, від 1 до 5, можна лишити " +
            "порожнім."
        )
      case CsvTemplate.Sleep =>
        tr(
          "Sleep: one row per night or nap. onset_at and wake_at are the times; kind is main or nap; quality, from " +
            "1 to 5, may stay empty.",
          "Сон: один рядок на кожну ніч або денний сон. onset_at і wake_at — час; kind — main або nap; quality, від " +
            "1 до 5, можна лишити порожнім."
        )
      case CsvTemplate.Meals =>
        tr(
          "Meals: one row per meal. meal_type is breakfast, lunch, dinner or snack; description may stay empty.",
          "Їжа: один рядок на кожен прийом їжі. meal_type — breakfast, lunch, dinner або snack; description можна " +
            "лишити порожнім."
        )

  /** `in.file.import` (BOT_UX section 22): a document sent to the chat is not opened. */
  def fileNotOpened: String = tr(
    "Files are imported in the app. This file was not opened.",
    "Файли імпортуються в застосунку. Цей файл бот не відкривав."
  )

  /** The size limit, said plainly with the refusal: a bot cannot fetch a file over 20 MB (PRODUCT_SPEC PC-06). */
  def sizeLimit: String = tr(
    "A chat bot cannot read a file larger than 20 MB, and health exports are often larger. The app reads the file " +
      "on your device, up to 4 GB, and sends only the records you confirm.",
    "Чат-бот не може прочитати файл, більший за 20 МБ, а експорти здоров’я часто більші. Застосунок читає файл " +
      "на вашому пристрої, до 4 ГБ, і надсилає лише записи, які ви підтвердите."
  )

  def entries: List[CopyEntry] =
    List(
      CopyEntry("import.card", card),
      CopyEntry("import.templates", templatesHint),
      CopyEntry("import.open", ImportInApp),
      CopyEntry("in.file.import", fileNotOpened),
      CopyEntry("in.file.size_limit", sizeLimit)
    ) ++ CsvTemplate.values.toList.flatMap: template =>
      List(
        CopyEntry(s"import.template.${template.kind}", templateLabel(template)),
        CopyEntry(s"import.template.${template.kind}.caption", templateCaption(template))
      )
end ImportCopy
