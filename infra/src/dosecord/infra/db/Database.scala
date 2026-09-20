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
