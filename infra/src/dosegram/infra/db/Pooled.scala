package dosegram.infra.db

import java.sql.Connection
import javax.sql.DataSource

/** One short transaction on a pooled connection: the stores of the edges need no transaction of their own, and none of
  * them runs inside the transaction of a chat event. Each runs as `dosegram_worker` (V14): the Mini App session lookup
  * authenticates by token before any account is known, and the sweeps of the edge stores cross accounts.
  */
private[db] final class Pooled(dataSource: DataSource):
  def apply[A](statement: Connection ?=> A): A =
    RowSecurity.transaction(dataSource, None)(conn => statement(using conn))
