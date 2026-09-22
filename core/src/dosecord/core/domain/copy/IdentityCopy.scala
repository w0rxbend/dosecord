package dosecord.core.domain.copy

/** Identity and onboarding copy (ROADMAP M0.12d, M1.4a catalogue). Account creation asks for the timezone only; the
  * handle is generated and the display name stays neutral (both editable later), so there is no copy asking for them.
  * Link and Restore have no copy here on purpose: they are hidden until M4.1/M5.3 and are never shown as "available
  * later".
  */
object IdentityCopy:

  /** First prompt of the Create flow. */
  val welcome = "Welcome to Dosecord! Let's get you set up."
  val timezonePrompt = "Pick your timezone:"

  /** The "Other" picker entry leads to free-text entry of an IANA zone. */
  val timezoneOther = "Other — type your timezone"
  val timezoneCustomPrompt = "Type your timezone (an IANA name like Europe/Kyiv)."

  /** An unrecognised zone re-shows the picker with this note (acceptance: invalid zone re-shows the picker). */
  val invalidZone = "I don't recognise that timezone — pick one from the list."

  /** The confirm step only accepts Yes/Change. */
  val confirmHint = "Tap Yes to confirm, or Change to pick another timezone."

  /** A second create from an already-linked identity is refused with this error and writes nothing. */
  val alreadyLinked = "This account is already set up — no need to create it again."

  /** Closing line of the Create flow. */
  val accountCreated = "You're all set — your account is ready. Try /mood or /help."

  /** Unlinked principals reach only identity flows and help (DESIGN.md section 6). */
  val createFirst = "Create your account first with /start."

  val entries: List[CopyEntry] = List(
    CopyEntry("identity.welcome", welcome),
    CopyEntry("identity.timezone.prompt", timezonePrompt),
    CopyEntry("identity.timezone.other", timezoneOther),
    CopyEntry("identity.timezone.custom_prompt", timezoneCustomPrompt),
    CopyEntry("identity.timezone.invalid", invalidZone),
    CopyEntry("identity.timezone.confirm_hint", confirmHint),
    CopyEntry("identity.already_linked", alreadyLinked),
    CopyEntry("identity.account_created", accountCreated),
    CopyEntry("identity.create_first", createFirst)
  )
end IdentityCopy
