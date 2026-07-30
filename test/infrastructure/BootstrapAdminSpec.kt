package com.puregoldbe.ibms.infrastructure

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.support.PostgresTestDb
import com.puregoldbe.ibms.support.testAppConfig
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import kotlinx.datetime.Clock
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Covers the first-run credential path for a fresh deployment.
 *
 * This existed but was unreachable: AppConfig defaulted BOOTSTRAP_ADMIN_PASSWORD to a
 * literal, so `bootstrapAdminPassword == null` was never true and every fresh database
 * got that literal installed on a sysadmin instead of a generated password — the
 * opposite of what five documents promised. With the default gone the branch is live,
 * so it gets pinned down here, end to end: generated, logged exactly once, and
 * actually usable to sign in.
 *
 * Deliberately provisions its own user rather than touching `mikepg`: the
 * Testcontainers database is shared across the whole suite and never truncated, and
 * `testAppConfig()` has already installed a password for that account.
 */
class BootstrapAdminSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject

    val users = ExposedUserRepository()

    /** A sysadmin with no password hash — the state V3's seeded admin starts in. */
    fun seedPasswordlessAdmin(): Pair<String, String> {
        val username = "bootstrap-gen-${UUID.randomUUID().toString().take(8)}"
        val id = transaction(PostgresTestDb.database) {
            val now = Clock.System.now()
            val created = users.create(
                input = ProvisionUserRequest(username = username, name = "Bootstrap Target", role = UserRole.SYSADMIN),
                passwordHash = "placeholder-to-be-cleared",
                tempPasswordExpiresAt = now + 1.days,
                at = now,
            )
            Users.update({ Users.id eq UUID.fromString(created.id) }) {
                it[Users.passwordHash] = null
                it[Users.mustChangePassword] = false
                it[Users.tempPasswordExpiresAt] = null
                it[Users.status] = UserStatus.ACTIVE
            }
            created.id
        }
        return username to id
    }

    /** Captures root-logger output for the duration of [block]. */
    fun <T> capturingLogs(block: () -> T): Pair<T, List<ILoggingEvent>> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        return try {
            block() to appender.list.toList()
        } finally {
            root.detachAppender(appender)
            appender.stop()
        }
    }

    Given("a seeded sysadmin with no password and BOOTSTRAP_ADMIN_PASSWORD unset") {
        When("the app boots") {
            Then("a password is generated, logged exactly once, and actually works") {
                val (username, id) = seedPasswordlessAdmin()
                val cfg = testAppConfig {
                    copy(auth = auth.copy(bootstrapAdminUsername = username, bootstrapAdminPassword = null))
                }

                val (_, events) = capturingLogs {
                    testApplication {
                        application { testModule(cfg) }
                        // Force the app to start so the bootstrap hook runs.
                        client.get("/api/health")
                    }
                }

                // A credential now exists and is flagged must-change, so the first
                // administrator goes through the same exchange as everyone else.
                val credentials = transaction(PostgresTestDb.database) { users.credentialsById(id) }
                credentials!!.passwordHash shouldNotBe null
                credentials.mustChangePassword shouldBe true

                // Logged once — it is the only channel to reach the first admin, and
                // repeating it would multiply the exposure.
                val announcements = events.filter { it.formattedMessage.contains("no BOOTSTRAP_ADMIN_PASSWORD set") }
                announcements shouldHaveSize 1

                // The logged value is the real credential, not a placeholder.
                val generated = announcements.single().argumentArray!!.last().toString()
                generated.isNotBlank() shouldBe true

                testApplication {
                    application { testModule(cfg) }
                    val login = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$username","password":"$generated"}""")
                    }
                    login.status shouldBe HttpStatusCode.OK
                    val data = login.bodyAsText().asJson().data()
                    data["outcome"]!!.jsonPrimitive.content shouldBe "password_change_required"
                    // Holding a temporary password is not being authenticated.
                    data["session"] shouldBe JsonNull
                }
            }
        }
    }

    Given("a sysadmin that already has a password") {
        When("the app boots with BOOTSTRAP_ADMIN_PASSWORD set to something else") {
            Then("the existing hash is left alone — restarting cannot take over the account") {
                val (username, id) = seedPasswordlessAdmin()
                val first = testAppConfig {
                    copy(auth = auth.copy(bootstrapAdminUsername = username, bootstrapAdminPassword = "First-Pw-9!"))
                }
                testApplication {
                    application { testModule(first) }
                    client.get("/api/health")
                }
                val installed = transaction(PostgresTestDb.database) { users.credentialsById(id) }!!.passwordHash

                val second = testAppConfig {
                    copy(auth = auth.copy(bootstrapAdminUsername = username, bootstrapAdminPassword = "Attacker-Pw-9!"))
                }
                testApplication {
                    application { testModule(second) }
                    client.get("/api/health")
                }

                transaction(PostgresTestDb.database) { users.credentialsById(id) }!!.passwordHash shouldBe installed
            }
        }
    }
})
