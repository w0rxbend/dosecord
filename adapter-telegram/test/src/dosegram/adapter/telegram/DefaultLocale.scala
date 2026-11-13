package dosegram.adapter.telegram

import java.util.Locale

/** Runs a test under a default locale whose case rules differ from the root locale: in Turkish an upper-case `I`
  * lower-cases to a dotless `ı`, so text folded with the default locale no longer equals its ASCII form.
  */
object DefaultLocale:

  def turkish[A](body: => A): A =
    val previous = Locale.getDefault
    Locale.setDefault(Locale.forLanguageTag("tr"))
    try body
    finally Locale.setDefault(previous)
