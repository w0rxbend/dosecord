package dosegram.core.application.privacy

import dosegram.core.ports.UnitOfWork

import java.util.UUID

/** The replay of the purge list (ARCHITECTURE SEC-06, SEC-16; finding 3 of the verification of 2026-11-14): every
  * account the list holds that the database still has, as a restored backup does, is deleted again, one transaction
  * each, before the process serves. An account the database does not hold is skipped, so a replay at every start is a
  * no-op once the deletions are applied.
  */
final class PurgeReplay(uow: UnitOfWork, deletions: Deletions):

  /** Deletes the `listed` accounts that still exist and returns how many it deleted. */
  def run(listed: List[UUID]): Int =
    listed.distinct.count: accountId =>
      uow.transaction: tx =>
        tx.accounts.settingsOf(accountId).isDefined && {
          deletions.replay(tx, accountId)
          true
        }
