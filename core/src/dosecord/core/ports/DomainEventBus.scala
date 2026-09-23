package dosecord.core.ports

/** The in-process `domain_events` consumer port (ADR-003: "consumed in-process after commit"; the M3.4 stats
  * invalidation subscriber hangs off this). Producers ([[dosecord.core.scheduling.ReminderLoop]],
  * [[dosecord.core.chat.ChatMediator]]) publish once the appending transaction has committed; a relay to an external
  * bus later sits behind the same port.
  */
trait DomainEventSubscriber:
  def onEvent(event: NewDomainEvent): Unit

trait DomainEventBus:
  def subscribe(subscriber: DomainEventSubscriber): Unit

  /** Delivers `event` to every subscriber; implementations isolate subscribers from each other. */
  def publish(event: NewDomainEvent): Unit

object DomainEventBus:

  val noop: DomainEventBus = new DomainEventBus:
    override def subscribe(subscriber: DomainEventSubscriber): Unit = ()
    override def publish(event: NewDomainEvent): Unit = ()

/** The in-process bus of ADR-003: subscribers are called synchronously on the publisher's thread after commit; one
  * failing subscriber never blocks the others (stats invalidation must not stall delivery).
  */
final class InProcessDomainEventBus extends DomainEventBus:
  private val subscribers = scala.collection.mutable.ListBuffer.empty[DomainEventSubscriber]

  override def subscribe(subscriber: DomainEventSubscriber): Unit = this.synchronized:
    subscribers += subscriber
    ()

  override def publish(event: NewDomainEvent): Unit =
    val snapshot = this.synchronized(subscribers.toList)
    snapshot.foreach: subscriber =>
      try subscriber.onEvent(event)
      catch case _: Exception => () // subscriber isolation; the event log row is already committed
