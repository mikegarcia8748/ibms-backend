package com.puregoldbe.ibms.adapter.db

import com.puregoldbe.ibms.infrastructure.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * Builds the HikariCP pool, runs Flyway migrations (schema.sql as V1..), and
 * connects Exposed. Flyway owns DDL; Exposed is query-only.
 */
fun buildDataSource(cfg: DbConfig): DataSource =
    HikariDataSource(HikariConfig().apply {
        jdbcUrl = cfg.url
        username = cfg.user
        password = cfg.password
        maximumPoolSize = cfg.poolSize
        driverClassName = "org.postgresql.Driver"
        poolName = "ibms-pool"
    })

fun migrate(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}

fun connectExposed(dataSource: DataSource): Database = Database.connect(dataSource)
