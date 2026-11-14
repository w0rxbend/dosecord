package dosegram.core.domain.copy

import dosegram.core.domain.input.InputText

/** Identity and onboarding copy (ROADMAP M0.12d, M1.4a catalogue). Account creation opens with the notice and the
  * consent of `ConsentCopy` and then asks for the timezone; the handle is generated and the display name stays neutral
  * (both editable later), so there is no copy asking for them. Link and Restore have no copy here on purpose: they are
  * hidden until M4.1/M5.3 and are never shown as "available later".
  */
object IdentityCopy:

  def timezonePrompt = tr("Pick your timezone:", "Виберіть свій часовий пояс:")

  /** The "Other" picker entry leads to free-text entry of an IANA zone. */
  def timezoneOther = tr("Other — type your timezone", "Інший — ввести вручну")
  def timezoneCustomPrompt =
    tr(
      "Type your city, like Kyiv or New York, a zone like Europe/Kyiv, or an offset like UTC+2.",
      "Введіть місто латиницею, як-от Kyiv чи New York, пояс на кшталт Europe/Kyiv або зсув, як-от UTC+2."
    )

  /** What to do when no zone was found (acceptance: invalid zone re-shows the picker). */
  def invalidZone =
    tr(
      "Pick one from the list, or tap Other and type a city like Kyiv, a zone like Europe/Kyiv, or an offset like UTC+2.",
      "Виберіть пояс зі списку або натисніть «Інший» і введіть місто латиницею, як-от Kyiv, пояс на кшталт Europe/Kyiv чи зсув, як-от UTC+2."
    )

  /** The note above the re-shown picker, naming what was typed (BOT_UX section 15.6). */
  def zoneNotFound(typed: String): String =
    tr(
      s"I could not find a time zone for \"${InputText.quoted(typed)}\". $invalidZone",
      s"Не вдалося знайти часовий пояс для «${InputText.quoted(typed)}». $invalidZone"
    )

  /** The confirm step only accepts Yes/Change. */
  def confirmHint = tr(
    "Tap Yes to confirm, or Change to pick another timezone.",
    "Натисніть «Так», щоб підтвердити, або «Змінити», щоб вибрати інший пояс."
  )

  /** A second create from an already-linked identity is refused with this error and writes nothing. */
  def alreadyLinked = tr(
    "This account is already set up — no need to create it again.",
    "Акаунт уже налаштовано — створювати його вдруге не потрібно."
  )

  /** Closing line of the Create flow. */
  def accountCreated = tr(
    "You're all set — your account is ready. Try /menu or /help.",
    "Готово — ваш акаунт створено. Спробуйте /menu або /help."
  )

  /** Unlinked principals reach only identity flows and help (DESIGN.md section 6). */
  def createFirst = tr("Create your account first with /start.", "Спершу створіть акаунт командою /start.")

  def entries: List[CopyEntry] = List(
    CopyEntry("identity.timezone.prompt", timezonePrompt),
    CopyEntry("identity.timezone.other", timezoneOther),
    CopyEntry("identity.timezone.custom_prompt", timezoneCustomPrompt),
    CopyEntry("identity.timezone.invalid", invalidZone),
    CopyEntry("identity.timezone.not_found", zoneNotFound("Atlantis")),
    CopyEntry("identity.timezone.confirm_hint", confirmHint),
    CopyEntry("identity.already_linked", alreadyLinked),
    CopyEntry("identity.account_created", accountCreated),
    CopyEntry("identity.create_first", createFirst)
  )
end IdentityCopy
