package dosecord.infra.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

object Database:
  /** HikariCP pool sized for virtual threads (ADR-002). */
  def pooled(url: String, user: String, password: String, maxPoolSize: Int = 10): HikariDataSource =
    val config = HikariConfig()
    config.setJdbcUrl(url)
    config.setUsername(user)
    config.setPassword(password)
    config.setMaximumPoolSize(maxPoolSize)
    HikariDataSource(config)

  /** Pool over a `DATABASE_URL` that carries its own credentials as pgjdbc query parameters (DESIGN.md section 12). */
  def pooled(url: String, maxPoolSize: Int): HikariDataSource =
    val config = HikariConfig()
    config.setJdbcUrl(url)
    config.setMaximumPoolSize(maxPoolSize)
    HikariDataSource(config)
