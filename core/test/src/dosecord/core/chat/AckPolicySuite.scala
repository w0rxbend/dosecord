package dosecord.core.chat

import dosecord.contracts.CommandSpec

class AckPolicySuite extends munit.FunSuite:
  test("a synthetic form-opening command leaves its initial acknowledgement for the modal"):
    assertEquals(
      AckPolicy.forCommand(CommandSpec("synthetic-form", "test", opensForm = true), CapabilityProfiles.Discord),
      AckPolicy.Ack.NoAck
    )
