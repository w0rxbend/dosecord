package dosegram.core.domain.copy

import dosegram.core.application.settings.SettingsChat

/** The settings hub's copy (BOT_UX section 17): every button of the card and of its day choices fits 24 characters in
  * both languages (BUX-KB-01), and each choice of a time is offered as it is stored.
  */
class SettingsCopySuite extends munit.FunSuite:

  private def labels: List[String] =
    SettingsCopy.buttons ++
      SettingsChat.DayAnchors.flatMap(anchor =>
        SettingsChat.anchorChips(anchor).map(at => SettingsCopy.anchorAt(anchor, at))
      ) ++
      SettingsChat.WorkChoices.map(work => SettingsCopy.workValue(work.days, work.start, work.end))

  test("every button of the settings hub fits 24 characters in English and in Ukrainian (BUX-KB-01)"):
    Lang.values.foreach: lang =>
      val tooLong = Lang.within(lang)(labels).filter(_.length > 24)
      assertEquals(tooLong, Nil, clue(lang))

  test("each anchor offers nine times, 30 minutes apart, bedtime past midnight"):
    val day = 24 * 60 * 60
    SettingsChat.DayAnchors.foreach: anchor =>
      val chips = SettingsChat.anchorChips(anchor)
      assertEquals(chips.size, 9, clue(anchor))
      chips
        .sliding(2)
        .foreach(pair => assertEquals((pair(1).toSecondOfDay - pair(0).toSecondOfDay + day) % day, 30 * 60, clue(pair)))
end SettingsCopySuite
