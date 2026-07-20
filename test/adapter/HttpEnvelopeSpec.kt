package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.TEST_PASSWORD
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * HTTP-level spec proving the API_CONTRACT response envelope: every JSON response
 * (success and error) is wrapped in {result, message, status, data}, and a rejected
 * JWT yields the same enveloped 401 rather than a bare Ktor challenge. Runs the real
 * composition root against Testcontainers Postgres.
 */
class HttpEnvelopeSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

    Given("the application booted with the unified envelope") {
        When("exercising representative success and error routes") {
            Then("all JSON responses are enveloped per the contract") {
                testApplication {
                    application { testModule() }

                    // --- success envelope + token mint (real login) ---
                    val seeded = signIn(UserRole.SYSADMIN)
                    val login = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"${seeded.username}","password":"$TEST_PASSWORD"}""")
                    }
                    login.status shouldBe HttpStatusCode.OK
                    val loginBody = login.bodyAsText().asJson()
                    loginBody["result"]!!.jsonPrimitive.content shouldBe "success"
                    loginBody["status"]!!.jsonPrimitive.content shouldBe "200"
                    val data = loginBody["data"]!!.jsonObject
                    val token = data["session"]!!.jsonObject["accessToken"]!!.jsonPrimitive.content
                    token.isNotBlank() shouldBe true
                    data["user"]!!.jsonObject["role"]!!.jsonPrimitive.content shouldBe "sysadmin"

                    // --- enveloped list payload behind auth ---
                    val users = client.get("/users") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    users.status shouldBe HttpStatusCode.OK
                    val usersBody = users.bodyAsText().asJson()
                    usersBody["result"]!!.jsonPrimitive.content shouldBe "success"
                    // list payloads are cursor pages: { items: [...], nextCursor: ... }
                    (usersBody["data"]!!.jsonObject["items"] is JsonArray) shouldBe true
                    usersBody["data"]!!.jsonObject.containsKey("nextCursor") shouldBe true

                    // --- /users/me returns the caller, enveloped (contract path) ---
                    val me = client.get("/users/me") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    me.status shouldBe HttpStatusCode.OK
                    val meBody = me.bodyAsText().asJson()
                    meBody["result"]!!.jsonPrimitive.content shouldBe "success"
                    meBody["data"]!!.jsonObject["username"]!!.jsonPrimitive.content shouldBe seeded.username

                    // --- enveloped 401 from the JWT challenge (no token) ---
                    val noAuth = client.get("/users")
                    noAuth.status shouldBe HttpStatusCode.Unauthorized
                    val noAuthBody = noAuth.bodyAsText().asJson()
                    noAuthBody["result"]!!.jsonPrimitive.content shouldBe "error"
                    noAuthBody["status"]!!.jsonPrimitive.content shouldBe "401"
                    noAuthBody["data"] shouldBe JsonNull

                    // --- enveloped domain error (404) ---
                    val notFound = client.get("/stores/00000000-0000-0000-0000-000000000000") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    notFound.status shouldBe HttpStatusCode.NotFound
                    val nfBody = notFound.bodyAsText().asJson()
                    nfBody["result"]!!.jsonPrimitive.content shouldBe "error"
                    nfBody["status"]!!.jsonPrimitive.content shouldBe "404"
                    nfBody["data"] shouldBe JsonNull
                }
            }
        }
    }
})
