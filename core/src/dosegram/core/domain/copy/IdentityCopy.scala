package dosegram.core.domain.copy

import dosegram.core.domain.input.InputText

/** Identity and onboarding copy (ROADMAP M0.12d, M1.4a catalogue). Account creation opens with the notice and the
  * consent of `ConsentCopy` and then asks for the timezone; the handle is generated and the display name stays neutral
  * (both editable later), so there is no copy asking for them. Link and Restore have no copy here on purpose: they are
  * hidden until M4.1/M5.3 and are never shown as "available later".
  */
object IdentityCopy:

  val timezonePrompt = "Pick your timezone:"

  /** The "Other" picker entry leads to free-text entry of an IANA zone. */
  val timezoneOther = "Other — type your timezone"
  val timezoneCustomPrompt =
    "Type your city, like Kyiv or New York, a zone like Europe/Kyiv, or an offset like UTC+2."

  /** What to do when no zone was found (acceptance: invalid zone re-shows the picker). */
  val invalidZone =
    "Pick one from the list, or tap Other and type a city like Kyiv, a zone like Europe/Kyiv, or an offset like UTC+2."

  /** The note above the re-shown picker, naming what was typed (BOT_UX section 15.6). */
  def zoneNotFound(typed: String): String =
    s"I could not find a time zone for \"${InputText.quoted(typed)}\". $invalidZone"

  /** The confirm step only accepts Yes/Change. */
  val confirmHint = "Tap Yes to confirm, or Change to pick another timezone."

  /** A second create from an already-linked identity is refused with this error and writes nothing. */
  val alreadyLinked = "This account is already set up — no need to create it again."

  /** Closing line of the Create flow. */
  val accountCreated = "You're all set — your account is ready. Try /menu or /help."

  /** Unlinked principals reach only identity flows and help (DESIGN.md section 6). */
  val createFirst = "Create your account first with /start."

  val entries: List[CopyEntry] = List(
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
