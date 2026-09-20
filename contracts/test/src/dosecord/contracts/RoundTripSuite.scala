package dosecord.contracts

import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll

import upickle.default.ReadWriter
import upickle.default.read
import upickle.default.write

import Gens.given

/** Acceptance 1: upickle round-trip property for every contracts type.
  */
class RoundTripSuite extends munit.ScalaCheckSuite:

  private def roundTrip[T: Arbitrary: ReadWriter](name: String): Unit =
    property(s"$name round-trips through upickle"):
      forAll { (value: T) =>
        read[T](write(value)) == value
      }

  roundTrip[AccountId]("AccountId")
  roundTrip[IdentityId]("IdentityId")
  roundTrip[EventId]("EventId")
  roundTrip[Source]("Source")
  roundTrip[Handle]("Handle")
  roundTrip[HhMm]("HhMm")
  roundTrip[MoodLevel]("MoodLevel")
  roundTrip[Weekday]("Weekday")
  roundTrip[Actor]("Actor")
  roundTrip[ReminderPolicyData]("ReminderPolicyData")
  roundTrip[MedicationData]("MedicationData")
  roundTrip[FixedTimeScheduleData]("FixedTimeScheduleData")
  roundTrip[Command]("Command")
  roundTrip[Event]("Event")
  roundTrip[Envelope[Command]]("Envelope[Command]")
  roundTrip[Envelope[Event]]("Envelope[Event]")
  roundTrip[PlatformIdentity]("PlatformIdentity")
  roundTrip[ChatRef]("ChatRef")
  roundTrip[MessageHandle]("MessageHandle")
  roundTrip[Principal]("Principal")
  roundTrip[LifecycleState]("LifecycleState")
  roundTrip[CallbackRef]("CallbackRef")
  roundTrip[Inbound]("Inbound")
  roundTrip[InboundEvent]("InboundEvent")
  roundTrip[TimeStyle]("TimeStyle")
  roundTrip[Inline]("Inline")
  roundTrip[Node]("Node")
  roundTrip[ChoiceStyle]("ChoiceStyle")
  roundTrip[ChoiceLayout]("ChoiceLayout")
  roundTrip[Choice]("Choice")
  roundTrip[ChoiceSet]("ChoiceSet")
  roundTrip[FieldType]("FieldType")
  roundTrip[Field]("Field")
  roundTrip[Form]("Form")
  roundTrip[NoticeLevel]("NoticeLevel")
  roundTrip[Notice]("Notice")
  roundTrip[Block]("Block")
  roundTrip[Visibility]("Visibility")
  roundTrip[Importance]("Importance")
  roundTrip[OutboundMessage]("OutboundMessage")
  roundTrip[EditCapability]("EditCapability")
  roundTrip[DeleteCapability]("DeleteCapability")
  roundTrip[DmInitiation]("DmInitiation")
  roundTrip[Markup]("Markup")
  roundTrip[CapabilityProfile]("CapabilityProfile")
  roundTrip[ChatError]("ChatError")
  roundTrip[RenderedChoice]("RenderedChoice")
  roundTrip[RenderedControls]("RenderedControls")
  roundTrip[ChoiceMapEntry]("ChoiceMapEntry")
  roundTrip[RenderedField]("RenderedField")
  roundTrip[RenderedForm]("RenderedForm")
  roundTrip[RenderedMessage]("RenderedMessage")
  roundTrip[RenderReport]("RenderReport")
  roundTrip[VendorOp]("VendorOp")
  roundTrip[CommandArg]("CommandArg")
  roundTrip[CommandSpec]("CommandSpec")
  roundTrip[Target]("Target")
  roundTrip[SendResult]("SendResult")
end RoundTripSuite
