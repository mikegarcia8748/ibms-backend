package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * End-to-end spec for provisioned-credential authentication, against the real
 * composition root and Testcontainers Postgres.
 *
 * The property this spec exists to pin down: holding a temporary password is not
 * the same as being authenticated. Logging in with one yields a challenge and
 * nothing else — no session row, no access token, and no way into the API — until
 * the holder sets a password of their own.
 */
class AuthFlowSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    // Unique per run so repeated suites never collide on the unique indexes.
    val suffix = System.nanoTime().toString().takeLast(12)
    val username = "prov$suffix"
    val email = "prov$suffix@puregold.com"
    val newPassword = "Chosen-Passw0rd!"

    Given("a sysadmin provisioning an account") {
        When("walking the whole flow: provision, temp login, forced change, session use") {
            Then("no session exists until the user sets their own password") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token

                    // --- 1. Sysadmin provisions the account ------------------
                    val provision = client.post("/users") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"username":"$username","email":"$email","name":"Provisioned User","role":"secretary"}""",
                        )
                    }
                    provision.status shouldBe HttpStatusCode.Created
                    val provisioned = provision.bodyAsText().asJson().data()
                    val temporaryPassword = provisioned.str("temporaryPassword")
                    temporaryPassword.isNotBlank() shouldBe true
                    provisioned["user"]!!.jsonObject["mustChangePassword"]!!.jsonPrimitive.boolean shouldBe true

                    // Provisioning is sysadmin-only.
                    client.post("/users") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"nope$suffix","email":"nope$suffix@x.com","name":"No"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // --- 2. Logging in with the temporary password -----------
                    val tempLogin = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$username","password":"$temporaryPassword"}""")
                    }
                    tempLogin.status shouldBe HttpStatusCode.OK
                    val tempLoginData = tempLogin.bodyAsText().asJson().data()
                    tempLoginData.str("outcome") shouldBe "password_change_required"
                    // The whole point: no session was created.
                    tempLoginData["session"] shouldBe JsonNull
                    val challengeToken = tempLoginData["passwordChange"]!!.jsonObject.str("challengeToken")

                    // --- 3. The challenge token is not a session token -------
                    client.get("/auth/me") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                    }.status shouldBe HttpStatusCode.Unauthorized
                    client.get("/users") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // A weak choice is refused, and the challenge stays usable.
                    client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"short"}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    // Reusing the temporary password as the "new" one is refused too.
                    client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"$temporaryPassword"}""")
                    }.status shouldBe HttpStatusCode.BadRequest

                    // --- 4. Setting a real password starts the session -------
                    val changed = client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"$newPassword"}""")
                    }
                    changed.status shouldBe HttpStatusCode.OK
                    val changedData = changed.bodyAsText().asJson().data()
                    changedData.str("outcome") shouldBe "authenticated"
                    changedData["user"]!!.jsonObject["mustChangePassword"]!!.jsonPrimitive.boolean shouldBe false
                    val session = changedData["session"]!!.jsonObject
                    val accessToken = session.str("accessToken")
                    val refreshToken = session.str("refreshToken")

                    // --- 5. The freshly minted token works on the API --------
                    val me = client.get("/auth/me") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
                    me.status shouldBe HttpStatusCode.OK
                    me.bodyAsText().asJson().data().str("username") shouldBe username

                    // --- 6. The temporary password is spent ------------------
                    client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$username","password":"$temporaryPassword"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // Replaying the challenge cannot reset the password again.
                    client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $challengeToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"Another-Passw0rd!"}""")
                    }.status shouldBe HttpStatusCode.Conflict

                    // --- 7. The chosen password logs straight in ------------
                    val realLogin = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$username","password":"$newPassword"}""")
                    }
                    realLogin.status shouldBe HttpStatusCode.OK
                    val realLoginData = realLogin.bodyAsText().asJson().data()
                    realLoginData.str("outcome") shouldBe "authenticated"
                    realLoginData["passwordChange"] shouldBe JsonNull

                    // Username is matched case-insensitively (CITEXT column).
                    client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"${username.uppercase()}","password":"$newPassword"}""")
                    }.status shouldBe HttpStatusCode.OK

                    // A wrong password is refused.
                    client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$username","password":"Wrong-Passw0rd!"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // An unknown username fails the same way — no user enumeration.
                    client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"ghost$suffix","password":"Wrong-Passw0rd!"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // --- 8. Refresh rotates the token pair ------------------
                    val refreshed = client.post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$refreshToken"}""")
                    }
                    refreshed.status shouldBe HttpStatusCode.OK
                    val rotated = refreshed.bodyAsText().asJson().data()["session"]!!.jsonObject
                    rotated.str("refreshToken") shouldNotBe refreshToken

                    // The consumed token is dead — a leaked refresh token is single-use.
                    client.post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$refreshToken"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // --- 9. Logout revokes the session ----------------------
                    val rotatedAccess = rotated.str("accessToken")
                    client.post("/auth/logout") {
                        header(HttpHeaders.Authorization, "Bearer $rotatedAccess")
                    }.status shouldBe HttpStatusCode.OK

                    client.post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"${rotated.str("refreshToken")}"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }

    Given("an account whose password a sysadmin resets") {
        When("a new temporary password is issued") {
            Then("the account is forced back through the change-password flow") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val resetUser = "reset$suffix"
                    val provisioned = client.post("/users") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"username":"$resetUser","email":"$resetUser@puregold.com","name":"Reset Me","role":"payables"}""",
                        )
                    }.bodyAsText().asJson().data()
                    val userId = provisioned["user"]!!.jsonObject.str("id")

                    // Redeem the first temporary password so the account is live.
                    val firstChallenge = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$resetUser","password":"${provisioned.str("temporaryPassword")}"}""")
                    }.bodyAsText().asJson().data()["passwordChange"]!!.jsonObject.str("challengeToken")

                    val settled = client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $firstChallenge")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"$newPassword"}""")
                    }.bodyAsText().asJson().data()["session"]!!.jsonObject
                    val liveRefreshToken = settled.str("refreshToken")

                    // --- the sysadmin resets it ------------------------------
                    val reset = client.post("/users/$userId/reset-password") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }
                    reset.status shouldBe HttpStatusCode.OK
                    val resetData = reset.bodyAsText().asJson().data()
                    resetData["user"]!!.jsonObject["mustChangePassword"]!!.jsonPrimitive.boolean shouldBe true

                    // The password the user had chosen no longer works...
                    client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$resetUser","password":"$newPassword"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // ...their live session was cut off...
                    client.post("/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"refreshToken":"$liveRefreshToken"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized

                    // ...and the new temporary password lands them back on the challenge.
                    val afterReset = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$resetUser","password":"${resetData.str("temporaryPassword")}"}""")
                    }.bodyAsText().asJson().data()
                    afterReset.str("outcome") shouldBe "password_change_required"
                    afterReset["session"] shouldBe JsonNull
                }
            }
        }
    }
})
