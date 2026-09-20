package dosecord.contracts

import upickle.default.ReadWriter

import java.util.UUID

/** Opaque id types (DESIGN.md section 2: "opaque types for ids"). The underlying value is always a UUID; the wrapper
  * keeps account, identity and event ids from being mixed up at compile time.
  */
opaque type AccountId = UUID
object AccountId:
  def apply(id: UUID): AccountId = id
  given ReadWriter[AccountId] = Json.given_ReadWriter_UUID
  extension (id: AccountId) def uuid: UUID = id

opaque type IdentityId = UUID
object IdentityId:
  def apply(id: UUID): IdentityId = id
  given ReadWriter[IdentityId] = Json.given_ReadWriter_UUID
  extension (id: IdentityId) def uuid: UUID = id

opaque type EventId = UUID
object EventId:
  def apply(id: UUID): EventId = id
  given ReadWriter[EventId] = Json.given_ReadWriter_UUID
  extension (id: EventId) def uuid: UUID = id
