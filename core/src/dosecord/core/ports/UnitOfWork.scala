package dosecord.core.ports

/** One database transaction; exposes the repositories bound to it (DESIGN.md section 7.4:
  * `uow.transaction: tx => ...`).
  */
trait Tx:
  def sessions: SessionRepository

trait UnitOfWork:
  def transaction[A](f: Tx => A): A
