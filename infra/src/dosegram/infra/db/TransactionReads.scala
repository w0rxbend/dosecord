package dosegram.infra.db

import dosegram.core.ports.AccountSettings
import dosegram.core.ports.ConsentPurpose

import java.util.UUID
import scala.collection.mutable

/** The account rows one transaction reads again and again (docs/PERFORMANCE.md): a request or a chat event asks for the
  * account's time zone, settings and consents once per domain it touches, up to 14 times for one Insights overview.
  * Each is read from the database once per transaction and kept here. A write through the repository that owns the row
  * forgets it, and so does an erasure or a rolled-back savepoint, so the transaction always reads its own writes; a
  * change that another transaction commits meanwhile is read by the next transaction. Lives exactly as long as its
  * transaction, on one thread.
  */
private[db] final class TransactionReads:
  private val timezones = mutable.Map.empty[UUID, Option[String]]
  private val settings = mutable.Map.empty[UUID, Option[AccountSettings]]
  private val consents = mutable.Map.empty[(UUID, ConsentPurpose), Boolean]

  def timezone(account: UUID)(read: => Option[String]): Option[String] = timezones.getOrElseUpdate(account, read)

  def settingsOf(account: UUID)(read: => Option[AccountSettings]): Option[AccountSettings] =
    settings.getOrElseUpdate(account, read)

  def consent(account: UUID, purpose: ConsentPurpose)(read: => Boolean): Boolean =
    consents.getOrElseUpdate((account, purpose), read)

  def forgetAccount(account: UUID): Unit =
    timezones.remove(account)
    settings.remove(account)

  def forgetConsents(account: UUID): Unit =
    consents.filterInPlace((key, _) => key._1 != account)

  def forgetAll(): Unit =
    timezones.clear()
    settings.clear()
    consents.clear()
