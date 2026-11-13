package dosegram.core.ports

import dosegram.core.application.FakeConsent
import dosegram.core.application.InMemoryTracking
import dosegram.core.application.TrackingTxDouble

/** ROADMAP G2.1: a double implements only the repository groups its suites use, and a call on any other group fails
  * with a message that names the group.
  */
class RepositoryGroupSuite extends munit.FunSuite:

  private val tx = TrackingTxDouble(InMemoryTracking(Map.empty), FakeConsent(Set.empty))

  test("a double answers on the groups it implements"):
    assertEquals(tx.tracking.rollups.claimDirty(10), Nil)
    assertEquals(tx.consents.granted(java.util.UUID.randomUUID()), Nil)

  test("a call on a group the double does not implement names the group"):
    val failure = intercept[UnsupportedOperationException](tx.occurrences)
    assert(failure.getMessage.contains("'occurrences'"), failure.getMessage)
