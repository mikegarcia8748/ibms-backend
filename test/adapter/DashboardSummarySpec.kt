package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.db.Accounts
import com.puregoldbe.ibms.adapter.db.toUuid
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.PostgresTestDb
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal

/**
 * Integration spec for `GET /dashboard/summary` (Manager's Dashboard features 1 & 2).
 *
 * Correctness is asserted two ways: the per-ISP row for a provider seeded uniquely
 * for this run is fully isolated (exact count + MRC), and the global active totals
 * are checked as before/after deltas (Kotest runs specs sequentially, so the delta
 * is stable). Also pins the role gating: MANAGER/FINANCE in, SECRETARY out, 401 anon.
 */
class DashboardSummarySpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    fun JsonObject.activeCount(): Int = this["totalActiveAccounts"]!!.jsonPrimitive.int
    fun JsonObject.activeMrc(): BigDecimal = BigDecimal(this["totalActiveMrc"]!!.jsonPrimitive.content)
    fun JsonObject.statusCount(status: String): Int = this["statusBreakdown"]!!.jsonArray
        .map { it.jsonObject }
        .first { it["status"]!!.jsonPrimitive.content == status }["count"]!!.jsonPrimitive.int

    Given("accounts across statuses and providers") {
        When("GET /dashboard/summary is called by a manager") {
            Then("totals, status breakdown and per-ISP breakdown reflect the seeded data") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val managerToken = signIn(UserRole.MANAGER).token
                    val s = System.nanoTime().toString()

                    suspend fun summary(): JsonObject =
                        client.get("/dashboard/summary") {
                            header(HttpHeaders.Authorization, "Bearer $managerToken")
                        }.bodyAsText().asJson().data()

                    val before = summary()

                    // --- seed: provider, store, 3 active accounts + 1 to-be-inactive ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"SumProv-$s","paymentScheduleDay":15}""")
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
                            """{"storeType":"puregold","branchCode":"SUM-$s","name":"Summary Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    suspend fun createAccount(num: String, rate: String): String =
                        client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $adminToken")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"$rate","installationDate":"2020-01-01"}""",
                            )
                        }.bodyAsText().asJson().data().str("id")

                    createAccount("sum-$s-1", "1000")
                    createAccount("sum-$s-2", "2000")
                    createAccount("sum-$s-3", "1500")
                    val inactiveId = createAccount("sum-$s-4", "999")

                    // Flip the 4th account to INACTIVE directly (grace expiry is time-gated).
                    transaction(PostgresTestDb.database) {
                        Accounts.update({ Accounts.id eq inactiveId.toUuid() }) {
                            it[Accounts.status] = AccountStatus.INACTIVE
                        }
                    }

                    val after = summary()

                    // Global active totals: exactly +3 accounts / +4500.00 MRC (inactive excluded).
                    (after.activeCount() - before.activeCount()) shouldBe 3
                    (after.activeMrc() - before.activeMrc()).compareTo(BigDecimal("4500.00")) shouldBe 0

                    // Status breakdown: the flipped account adds exactly one inactive.
                    (after.statusCount("inactive") - before.statusCount("inactive")) shouldBe 1

                    // Per-ISP row is fully isolated to this run's provider.
                    val row = after["byProvider"]!!.jsonArray.map { it.jsonObject }
                        .first { it["providerId"]!!.jsonPrimitive.content == providerId }
                    row["activeAccountCount"]!!.jsonPrimitive.int shouldBe 3
                    BigDecimal(row.str("activeMrc")).compareTo(BigDecimal("4500.00")) shouldBe 0
                    row.str("providerName") shouldBe "SumProv-$s"
                }
            }
        }

        When("GET /dashboard/summary is called by finance") {
            Then("it returns 200") {
                testApplication {
                    application { testModule() }
                    val financeToken = signIn(UserRole.FINANCE).token
                    client.get("/dashboard/summary") {
                        header(HttpHeaders.Authorization, "Bearer $financeToken")
                    }.status shouldBe HttpStatusCode.OK
                }
            }
        }

        When("GET /dashboard/summary is called by a secretary") {
            Then("it returns 403") {
                testApplication {
                    application { testModule() }
                    val secretaryToken = signIn(UserRole.SECRETARY).token
                    client.get("/dashboard/summary") {
                        header(HttpHeaders.Authorization, "Bearer $secretaryToken")
                    }.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        When("GET /dashboard/summary is called without authentication") {
            Then("it returns 401") {
                testApplication {
                    application { testModule() }
                    client.get("/dashboard/summary").status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }
})
