package com.puregoldbe.ibms.support

import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/**
 * How integration specs obtain a token.
 *
 * There is deliberately no back door in the application for this. The helper
 * seeds a user straight through the repository — the same thing a sysadmin's
 * `POST /users` would persist, minus the temporary-password state — and then
 * signs in over the real `POST /auth/login`. So every spec exercises the actual
 * authentication path, and the production build has no password-bypass route to
 * accidentally ship enabled.
 *
 * Each call creates its own user: specs share one Postgres container, so a
 * helper that mutated a single shared account would couple them through the
 * database and break on reordering.
 */

/** Password given to every seeded spec user. Satisfies `PasswordPolicy`. */
const val TEST_PASSWORD = "Spec-Passw0rd!x"

private val hasher = BcryptPasswordHasher(BcryptPasswordHasher.MIN_COST)
private val users = ExposedUserRepository()

/** A signed-in spec user: the bearer token plus the identity to assert against. */
data class TestSession(
    val token: String,
    val userId: String,
    val username: String,
)

/**
 * Seed an already-onboarded user in [role] and log them in.
 *
 * @return the access token and the identity it belongs to.
 */
suspend fun ApplicationTestBuilder.signIn(role: UserRole = UserRole.SYSADMIN): TestSession {
    val suffix = "${role.name.lowercase().take(4)}${System.nanoTime().toString().takeLast(11)}"

    val userId = transaction(PostgresTestDb.database) {
        val now = Clock.System.now()
        val passwordHash = hasher.hash(TEST_PASSWORD)
        val created = users.create(
            input = ProvisionUserRequest(
                username = suffix,
                name = "Spec ${role.name.lowercase()}",
                role = role,
            ),
            passwordHash = passwordHash,
            tempPasswordExpiresAt = now + 1.days,
            at = now,
        )
        // create() always lands in the temporary-password state; clear the flag so
        // the user logs straight into a session. Specs that care about the forced
        // change drive it through the API instead (see AuthFlowSpec).
        users.setPassword(
            id = created.id,
            passwordHash = passwordHash,
            mustChangePassword = false,
            tempPasswordExpiresAt = null,
            at = now,
        )
        created.id
    }

    val response = client.post("/auth/login") {
        contentType(ContentType.Application.Json)
        setBody("""{"username":"$suffix","password":"$TEST_PASSWORD"}""")
    }
    check(response.status == HttpStatusCode.OK) {
        "spec sign-in failed for $suffix: ${response.status} ${response.bodyAsText()}"
    }
    val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!
        .jsonObject["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content

    return TestSession(token = token, userId = userId, username = suffix)
}
