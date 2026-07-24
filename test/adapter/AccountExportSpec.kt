package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Integration spec for the account Excel export endpoint `GET /exports/accounts.xlsx`.
 *
 * Seeds providers, stores, and accounts with different statuses, then downloads
 * the Excel file with various filter combinations and asserts headers, magic
 * bytes, and error cases.
 */
class AccountExportSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    Given("a set of accounts across providers and statuses") {
        When("GET /exports/accounts.xlsx is called with no filters") {
            Then("it returns 200 with a valid XLSX binary") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    // --- seed: provider ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"ExpProv-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: attachment (store proof) ---
                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    // --- seed: store ---
                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"EXP-$s","name":"Export Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    // --- seed: 2 accounts ---
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"exp-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
                        )
                    }.status shouldBe HttpStatusCode.Created
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"exp-$s-2","providerId":"$providerId","storeId":"$storeId","rate":"2000","installationDate":"2020-01-01"}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    // --- export with no filters ---
                    val resp = client.get("/exports/accounts.xlsx") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }

                    resp.status shouldBe HttpStatusCode.OK
                    resp.headers[HttpHeaders.ContentType]!! shouldContain
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    resp.headers[HttpHeaders.ContentDisposition]!! shouldContain ".xlsx"

                    val bytes = resp.readRawBytes()
                    (bytes.size > 0) shouldBe true
                    // XLSX (ZIP) magic bytes: PK
                    val magic = bytes.take(2).toByteArray().decodeToString()
                    magic shouldBe "PK"
                }
            }
        }

        When("GET /exports/accounts.xlsx is called with providerId filter") {
            Then("it returns 200 with a valid XLSX binary") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"FiltProv-$s","paymentScheduleDay":10}""")
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
                            """{"storeType":"puregold","branchCode":"FILT-$s","name":"Filter Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"filt-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1500","installationDate":"2020-01-01"}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val resp = client.get("/exports/accounts.xlsx?providerId=$providerId") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }

                    resp.status shouldBe HttpStatusCode.OK
                    resp.headers[HttpHeaders.ContentDisposition]!! shouldContain ".xlsx"
                    val bytes = resp.readRawBytes()
                    bytes.take(2).toByteArray().decodeToString() shouldBe "PK"
                }
            }
        }

        When("GET /exports/accounts.xlsx is called with status=active filter") {
            Then("it returns 200 with a valid XLSX binary") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"StatProv-$s","paymentScheduleDay":20}""")
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
                            """{"storeType":"puregold","branchCode":"STAT-$s","name":"Status Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"stat-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"3000","installationDate":"2020-01-01"}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val resp = client.get("/exports/accounts.xlsx?status=active") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }

                    resp.status shouldBe HttpStatusCode.OK
                    resp.headers[HttpHeaders.ContentDisposition]!! shouldContain ".xlsx"
                    val bytes = resp.readRawBytes()
                    bytes.take(2).toByteArray().decodeToString() shouldBe "PK"
                }
            }
        }

        When("GET /exports/accounts.xlsx is called with a non-existent providerId") {
            Then("it returns 404") {
                testApplication {
                    application { testModule() }

                    val adminToken = signIn(UserRole.SYSADMIN).token

                    val resp = client.get("/exports/accounts.xlsx?providerId=00000000-0000-0000-0000-000000000000") {
                        header(HttpHeaders.Authorization, "Bearer $adminToken")
                    }

                    resp.status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        When("GET /exports/accounts.xlsx is called without authentication") {
            Then("it returns 401") {
                testApplication {
                    application { testModule() }

                    val resp = client.get("/exports/accounts.xlsx")

                    resp.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }
})
