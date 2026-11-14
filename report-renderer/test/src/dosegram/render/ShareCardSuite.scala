package dosegram.render

import dosegram.render.blob.MoodState
import dosegram.render.card.Privacy
import dosegram.render.card.ShareCard
import dosegram.render.card.ShareData
import dosegram.render.card.ShareFact
import dosegram.render.copy.UkrainianImageCopy
import dosegram.render.scene.Node
import dosegram.render.tokens.Domain
import dosegram.render.tokens.Theme

/** IMG-17 (VISUALS section 6.18): the shareable card carries one whitelisted fact and nothing else. */
class ShareCardSuite extends munit.FunSuite:

  private val week = Vector(true, true, false, true, true, false, true)

  test("VA-IMG-17a: the share facts hold a domain, seven day marks or a mood state, and nothing else"):
    val day = ShareFact.DayCount(Domain.Water, week)
    assertEquals(day.productElementNames.toList, List("domain", "days"))
    assertEquals(ShareFact.WeekMood(MoodState(4, 3)).productElementNames.toList, List("state"))
    assertEquals(ShareFact.DayCount.Domains, Set(Domain.Water, Domain.Sleep, Domain.Mood))
    intercept[IllegalArgumentException](ShareFact.DayCount(Domain.Medication, week))
    intercept[IllegalArgumentException](ShareFact.DayCount(Domain.Food, week))
    intercept[IllegalArgumentException](ShareFact.DayCount(Domain.Water, Vector.fill(7)(false)))

  test("the card draws the sentence of its text and no other number; the wordmark has no link or handle"):
    val card = ShareCard.render(ShareData(ShareFact.DayCount(Domain.Water, week)), Samples.context(Theme.Light, Privacy.Named))
    assertEquals(card.text.full, "Water goal reached on 5 of 7 days this week.")
    val texts = Node.flatten(card.scene.nodes).collect { case text: Node.Text => text.content }
    assertEquals(texts.filter(_.exists(_.isDigit)), Vector("5 of 7 days"))
    assert(texts.contains("Dosegram"), texts)
    val words = (texts :+ card.text.full :+ card.text.caption).mkString(" ")
    List("http", "@", "t.me", "dosegram_").foreach(word => assert(!words.contains(word), word))

  test("in discreet mode the caption is generic and the photo is behind a spoiler; Ukrainian reads whole"):
    val discreet = ShareCard.render(ShareData(ShareFact.WeekMood(MoodState(5, 3))), Samples.context(Theme.Dark, Privacy.Discreet))
    assertEquals((discreet.text.caption, discreet.hasSpoiler), ("A card to share.", true))
    val uk = ShareCard.render(
      ShareData(ShareFact.DayCount(Domain.Sleep, Vector.fill(7)(true))),
      Samples.context(Theme.Light, Privacy.Named, UkrainianImageCopy)
    )
    assertEquals(uk.text.full, "Сон записано за 7 з 7 днів цього тижня.")
