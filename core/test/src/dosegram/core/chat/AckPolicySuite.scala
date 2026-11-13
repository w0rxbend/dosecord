package dosegram.core.chat

import dosegram.core.chat.MediatorFakes.RecordingInteractionHandle

import scala.jdk.CollectionConverters.*

class AckPolicySuite extends munit.FunSuite:
  test("a single-shot vendor is answered once, with no toast, before database work"):
    val interaction = RecordingInteractionHandle()
    AckPolicy.ackBeforeWork(CapabilityProfiles.Telegram, interaction)
    AckPolicy.ackBeforeWork(CapabilityProfiles.Telegram, interaction)
    assertEquals(interaction.calls.asScala.toList, List("answer()"))

  test("a vendor without a transient ack is not answered"):
    val interaction = RecordingInteractionHandle()
    AckPolicy.ackBeforeWork(CapabilityProfiles.Console, interaction)
    assertEquals(interaction.calls.asScala.toList, Nil)
