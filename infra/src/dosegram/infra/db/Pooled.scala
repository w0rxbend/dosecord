package dosegram.infra.db

import java.sql.Connection
import javax.sql.DataSource

/** One statement on a pooled connection in auto-commit mode: the stores of the edges need no transaction of their own,
  * and none of them runs inside the transaction of a chat event.
  */
private[db] final class Pooled(dataSource: DataSource):
  def apply[A](statement: Connection ?=> A): A =
    val conn = dataSource.getConnection
    try statement(using conn)
    finally conn.close()
