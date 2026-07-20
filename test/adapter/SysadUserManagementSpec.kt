package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
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
 * End-to-end spec for System Admin user management: list users, provision
 * without email, toggle user status. Exercises the real composition root
 * against Testcontainers Postgres.
 */
class SysadUserManagementSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    val suffix = System.nanoTime().toString().takeLast(12)

    Given("a sysadmin managing user accounts") {
        When("provisioning a user without email") {
            Then("the user is created with ACTIVE status and a temporary password") {
                testApplication {
                    application { testModule() }
                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val newUsername = "newuser$suffix"
                    val provision = client.post("/users") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{
                                "username":"$newUsername",
                                "name":"Juan Dela Cruz",
                                "firstName":"Juan",
                                "middleInitial":"D.",
                                "lastName":"Dela Cruz",
                                "employeeNumber":"010005530",
                                "role":"secretary"
                            }""",
                        )
                    }
                    provision.status shouldBe HttpStatusCode.Created
                    val data = provision.bodyAsText().asJson().data()
                    val user = data["user"]!!.jsonObject
                    user.str("username") shouldBe newUsername
                    user.str("status") shouldBe "active"
                    user["mustChangePassword"]!!.jsonPrimitive.boolean shouldBe true
                    data.str("temporaryPassword").isNotBlank() shouldBe true
                }
            }
        }

        When("listing users") {
            Then("each user includes a status field") {
                testApplication {
                    application { testModule() }
                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val response = client.get("/users") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }
                    response.status shouldBe HttpStatusCode.OK
                    val data = response.bodyAsText().asJson().data()
                    val items = data["items"]!!.jsonArray
                    items.isNotEmpty() shouldBe true
                    // Every user in the list has a status field
                    items.forEach { item ->
                        item.jsonObject.containsKey("status") shouldBe true
                    }
                }
            }
        }

        When("toggling a user's status to inactive") {
            Then("the user's status is updated and they cannot log in") {
                testApplication {
                    application { testModule() }
                    val adminToken = signIn(UserRole.SYSADMIN).token

                    // Provision a user first
                    val targetUsername = "toggle$suffix"
                    val provisioned = client.post("/users") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"username":"$targetUsername","name":"Toggle User","role":"finance"}""",
                        )
                    }
                    provisioned.status shouldBe HttpStatusCode.Created
                    val provData = provisioned.bodyAsText().asJson().data()
                    val userId = provData["user"]!!.jsonObject.str("id")
                    val tempPassword = provData.str("temporaryPassword")

                    // Redeem temporary password to make the account fully live
                    val challenge = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$targetUsername","password":"$tempPassword"}""")
                    }.bodyAsText().asJson().data()["passwordChange"]!!.jsonObject.str("challengeToken")

                    client.post("/auth/password/change") {
                        header(HttpHeaders.Authorization, "Bearer $challenge")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newPassword":"Real-Passw0rd!"}""")
                    }.status shouldBe HttpStatusCode.OK

                    // Now deactivate the user
                    val statusUpdate = client.patch("/users/$userId/status") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"inactive"}""")
                    }
                    statusUpdate.status shouldBe HttpStatusCode.OK
                    val updatedUser = statusUpdate.bodyAsText().asJson().data()
                    updatedUser.str("status") shouldBe "inactive"

                    // The deactivated user can no longer log in
                    val loginAttempt = client.post("/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"$targetUsername","password":"Real-Passw0rd!"}""")
                    }
                    loginAttempt.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        When("a non-sysadmin tries to toggle status") {
            Then("it returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val secretaryToken = signIn(UserRole.SECRETARY).token

                    val response = client.patch("/users/some-id/status") {
                        header(HttpHeaders.Authorization, "Bearer $secretaryToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"inactive"}""")
                    }
                    response.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        When("toggling status for a non-existent user") {
            Then("it returns 404") {
                testApplication {
                    application { testModule() }
                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val response = client.patch("/users/00000000-0000-0000-0000-000000000000/status") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"inactive"}""")
                    }
                    response.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        When("filtering users by status") {
            Then("only users matching the status are returned") {
                testApplication {
                    application { testModule() }
                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val response = client.get("/users?status=active") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }
                    response.status shouldBe HttpStatusCode.OK
                    val items = response.bodyAsText().asJson().data()["items"]!!.jsonArray
                    items.forEach { item ->
                        item.jsonObject.str("status") shouldBe "active"
                    }
                }
            }
        }
    }
})
