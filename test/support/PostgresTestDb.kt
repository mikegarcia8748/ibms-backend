package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.adapter.db.buildDataSource
import com.puregoldbe.ibms.adapter.db.connectExposed
import com.puregoldbe.ibms.adapter.db.migrate
import com.puregoldbe.ibms.infrastructure.config.DbConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * A single Postgres 16 container + migrated Exposed connection, shared by the
 * integration specs. Migrations (V1..V3) run once on start, so specs exercise the
 * real schema — enum casting, numeric(14,2), timestamptz, and FK integrity.
 */
object PostgresTestDb {
    private val container = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
        .withDatabaseName("ibms")
        .withUsername("ibms")
        .withPassword("ibms")

    val database: Database by lazy {
        container.start()
        val ds = buildDataSource(DbConfig(container.jdbcUrl, container.username, container.password, poolSize = 3))
        migrate(ds)
        connectExposed(ds)
    }
}
