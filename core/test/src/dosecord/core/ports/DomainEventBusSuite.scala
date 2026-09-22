package dosecord.core.ports

/** ADR-003's in-process `domain_events` consumption (ROADMAP M1.7 scaffold): subscribers receive events after commit
  * and one failing subscriber never blocks the others.
  */
class DomainEventBusSuite extends munit.FunSuite:

  private def event(id: String): NewDomainEvent =
    NewDomainEvent(
      id = java.util.UUID.randomUUID(),
      eventType = id,
      source = "dosecord.test",
      subject = None,
      accountId = None,
      correlationId = None,
      causationId = None,
      actorJson = "{}",
      occurredAt = java.time.Instant.EPOCH,
      dataJson = "{}"
    )

  test("subscribers receive published events in subscription order"):
    val bus = InProcessDomainEventBus()
    val first = List.newBuilder[NewDomainEvent]
    val second = List.newBuilder[NewDomainEvent]
    bus.subscribe(e => first += e)
    bus.subscribe(e => second += e)
    val e1 = event("one")
    val e2 = event("two")
    bus.publish(e1)
    bus.publish(e2)
    assertEquals(first.result(), List(e1, e2))
    assertEquals(second.result(), List(e1, e2))

  test("a failing subscriber does not block the others"):
    val bus = InProcessDomainEventBus()
    val seen = List.newBuilder[NewDomainEvent]
    bus.subscribe(_ => throw new RuntimeException("subscriber boom"))
    bus.subscribe(e => seen += e)
    val e = event("after-failure")
    bus.publish(e)
    assertEquals(seen.result(), List(e))

  test("the no-op bus accepts subscribers and swallows publishes"):
    val bus = DomainEventBus.noop
    bus.subscribe(_ => throw new RuntimeException("never called"))
    bus.publish(event("ignored"))
