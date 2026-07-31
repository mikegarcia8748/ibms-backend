package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.Stores
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.PostgresTestDb
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration spec for the dashboard account/store listings:
 *  - `GET /dashboard/accounts`          (feature 3, denormalized with names)
 *  - `GET /dashboard/archived-accounts` (feature 6a, status = inactive)
 *  - `GET /dashboard/archived-stores`   (feature 6b, status = closed)
 */
class DashboardAccountsSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.items(): List<JsonObject> = this.data()["items"]!!.jsonArray.map { it.jsonObject }
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    Given("an account with an associated store") {
        When("GET /dashboard/accounts is filtered by that provider") {
            Then("it returns denormalized rows carrying provider and store names") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val managerToken = signIn(UserRole.MANAGER).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"AcctProv-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"ACC-$s","name":"Account Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val subProof = uploadPdfProof(adminToken, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"acc-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1234","installationDate":"2020-01-01","subscriptionProofIds":["$subProof"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val resp = client.get("/dashboard/accounts?providerId=$providerId") {
                        header(HttpHeaders.Authorization, "Bearer $managerToken")
                    }
                    resp.status shouldBe HttpStatusCode.OK
                    val items = resp.bodyAsText().asJson().items()
                    items.size shouldBe 1
                    val row = items.first()
                    row.str("accountNumber") shouldBe "acc-$s-1"
                    row.str("providerName") shouldBe "AcctProv-$s"
                    row.str("branchCode") shouldBe "ACC-$s"
                    row.str("storeName") shouldBe "Account Store"
                    row.str("rate") shouldBe "1234.00"
                    row.str("status") shouldBe "active"
                    row["id"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                }
            }
        }

        When("GET /dashboard/accounts is called by a secretary") {
            Then("it returns 403") {
                testApplication {
                    application { testModule() }
                    val secretaryToken = signIn(UserRole.SECRETARY).token
                    client.get("/dashboard/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $secretaryToken")
                    }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    Given("one active and one archived account under a provider") {
        When("GET /dashboard/archived-accounts is filtered by that provider") {
            Then("only the inactive account is returned") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val managerToken = signIn(UserRole.MANAGER).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"ArchProv-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"ARC-$s","name":"Archive Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    suspend fun createAccount(num: String): String {
                        val subProof = uploadPdfProof(adminToken, "subscription_proof")
                        val res = client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$subProof"]}""",
                            )
                        }
                        // Assert before reading `data`: on an error envelope `data` is null, and
                        // the resulting JsonNull cast would mask the real status.
                        res.status shouldBe HttpStatusCode.Created
                        return res.bodyAsText().asJson().data().str("id")
                    }

                    createAccount("arc-$s-active")
                    val inactiveId = createAccount("arc-$s-inactive")
                    transaction(PostgresTestDb.database) {
                        Accounts.update({ Accounts.id eq inactiveId.toUuid() }) {
                            it[Accounts.status] = AccountStatus.INACTIVE
                        }
                    }

                    val resp = client.get("/dashboard/archived-accounts?providerId=$providerId") {
                        header(HttpHeaders.Authorization, "Bearer $managerToken")
                    }
                    resp.status shouldBe HttpStatusCode.OK
                    val items = resp.bodyAsText().asJson().items()
                    items.map { it.str("status") }.toSet() shouldBe setOf("inactive")
                    val numbers = items.map { it.str("accountNumber") }
                    numbers shouldContain "arc-$s-inactive"
                    numbers shouldNotContain "arc-$s-active"
                }
            }
        }
    }

    Given("a closed store") {
        When("GET /dashboard/archived-stores is queried by its branch code") {
            Then("the closed store is returned") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val managerToken = signIn(UserRole.MANAGER).token
                    val s = System.nanoTime().toString()

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val branchCode = "CLS-$s"
                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"$branchCode","name":"Closed Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    transaction(PostgresTestDb.database) {
                        Stores.update({ Stores.id eq storeId.toUuid() }) {
                            it[Stores.status] = StoreStatus.CLOSED
                        }
                    }

                    val resp = client.get("/dashboard/archived-stores?q=$branchCode") {
                        header(HttpHeaders.Authorization, "Bearer $managerToken")
                    }
                    resp.status shouldBe HttpStatusCode.OK
                    val items = resp.bodyAsText().asJson().items()
                    items.size shouldBe 1
                    items.first().str("branchCode") shouldBe branchCode
                    items.first().str("status") shouldBe "closed"
                }
            }
        }
    }
})
