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
 * End-to-end integration spec for POST /accounts/isp.
 *
 * Exercises the full ISP account creation flow (provider → store → proof →
 * account), proration logic, role enforcement, input validation, conflict
 * detection, and whitespace trimming against Testcontainers Postgres.
 */
class CreateISPAccountSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.boolean

    /** Presign and "upload" an attachment; returns the attachmentId. */
    suspend fun ApplicationTestBuilder.uploadAttachment(
        token: String,
        purpose: String = "subscription_proof",
        fileName: String = "proof.pdf",
        contentType: String = "application/pdf",
    ): String {
        val presign = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"$fileName","contentType":"$contentType","purpose":"$purpose"}""")
        }
        presign.status shouldBe HttpStatusCode.OK
        val pd = presign.bodyAsText().asJson().data()
        val attachmentId = pd.str("attachmentId")
        val uploadUrl = pd.str("url").removePrefix("http://localhost:8080")

        val put = client.put(uploadUrl) { setBody("test-bytes".toByteArray()) }
        put.status shouldBe HttpStatusCode.OK
        return attachmentId
    }

    /** Creates a provider; returns its id. */
    suspend fun ApplicationTestBuilder.createProvider(token: String, payDay: Int = 15): String {
        val res = client.post("/providers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"ISP-Prov-${System.nanoTime()}","paymentScheduleDay":$payDay}""")
        }
        res.status shouldBe HttpStatusCode.Created
        return res.bodyAsText().asJson().data().str("id")
    }

    /** Creates a store (requires installation proof); returns its id. */
    suspend fun ApplicationTestBuilder.createStore(token: String): String {
        val proofId = uploadAttachment(token, purpose = "installation_proof", fileName = "install.pdf")
        val res = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"storeType":"puregold","branchCode":"BC-${System.nanoTime()}","name":"ISP-Store","proofOfInstallationId":"$proofId"}""",
            )
        }
        res.status shouldBe HttpStatusCode.Created
        return res.bodyAsText().asJson().data().str("id")
    }

    // =================================================================
    //  HAPPY PATH (FULL FLOW)
    // =================================================================

    Given("a secretary with all prerequisites set up") {
        When("creating an ISP account with valid data (installDay > payDay → prorated)") {
            Then("returns 201 with the created account and isProrated = true") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()

                    val providerId = createProvider(admin.token, payDay = 15)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val accNum = "ISP-${System.nanoTime()}"
                    val circuitId = "CIR-${System.nanoTime()}"
                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "accountNumber":"$accNum",
                              "circuitId":"$circuitId",
                              "providerId":"$providerId",
                              "storeId":"$storeId",
                              "rate":"2500.00",
                              "installationDate":"2026-07-20",
                              "subscriptionProofId":"$proofId"
                            }
                            """.trimIndent(),
                        )
                    }
                    res.status shouldBe HttpStatusCode.Created
                    val d = res.bodyAsText().asJson().data()
                    d.str("accountNumber") shouldBe accNum
                    d.str("circuitId") shouldBe circuitId
                    d.str("rate") shouldBe "2500.00"
                    d.bool("isProrated") shouldBe true
                    d["subscriptionProofIds"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf(proofId)
                }
            }
        }
    }

    // =================================================================
    //  PRORATION = FALSE
    // =================================================================

    Given("installation date before payment schedule day") {
        When("creating ISP account with installationDate day 10, provider day 15") {
            Then("returns 201 with isProrated = false") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()

                    val providerId = createProvider(admin.token, payDay = 15)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """
                            {
                              "accountNumber":"ISP-NP-${System.nanoTime()}",
                              "circuitId":"CIR-NP-${System.nanoTime()}",
                              "providerId":"$providerId",
                              "storeId":"$storeId",
                              "rate":"1500.00",
                              "installationDate":"2026-07-10",
                              "subscriptionProofId":"$proofId"
                            }
                            """.trimIndent(),
                        )
                    }
                    res.status shouldBe HttpStatusCode.Created
                    val d = res.bodyAsText().asJson().data()
                    d.bool("isProrated") shouldBe false
                }
            }
        }
    }

    // =================================================================
    //  AUTH BOUNDARY
    // =================================================================

    Given("role-based access control") {
        When("MANAGER tries to create ISP account") {
            Then("returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val mgr = signIn(UserRole.MANAGER)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"X","circuitId":"X","providerId":"00000000-0000-0000-0000-000000000000","storeId":"00000000-0000-0000-0000-000000000000","rate":"100.00","installationDate":"2026-07-10","subscriptionProofId":"x"}""")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }

        When("unauthenticated request") {
            Then("returns 401 Unauthorized") {
                testApplication {
                    application { testModule() }

                    val res = client.post("/accounts/isp") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"X","circuitId":"X","providerId":"00000000-0000-0000-0000-000000000000","storeId":"00000000-0000-0000-0000-000000000000","rate":"100.00","installationDate":"2026-07-10","subscriptionProofId":"x"}""")
                    }
                    res.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }

    // =================================================================
    //  VALIDATION ERRORS
    // =================================================================

    Given("invalid input data") {
        When("accountNumber is blank") {
            Then("returns 400 with validation message") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"  ","circuitId":"CIR-1","providerId":"$providerId","storeId":"$storeId","rate":"100.00","installationDate":"2026-07-10","subscriptionProofId":"$proofId"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("circuitId is null") {
            Then("returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"ISP-V-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId","rate":"100.00","installationDate":"2026-07-10","subscriptionProofId":"$proofId"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("subscriptionProofId references non-existent attachment") {
            Then("returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"ISP-V-${System.nanoTime()}","circuitId":"CIR-1","providerId":"$providerId","storeId":"$storeId","rate":"100.00","installationDate":"2026-07-10","subscriptionProofId":"00000000-0000-0000-0000-000000000000"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("rate is zero") {
            Then("returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"ISP-V-${System.nanoTime()}","circuitId":"CIR-1","providerId":"$providerId","storeId":"$storeId","rate":"0","installationDate":"2026-07-10","subscriptionProofId":"$proofId"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("installationDate is in the future") {
            Then("returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"ISP-V-${System.nanoTime()}","circuitId":"CIR-1","providerId":"$providerId","storeId":"$storeId","rate":"100.00","installationDate":"2099-01-01","subscriptionProofId":"$proofId"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    // =================================================================
    //  CONFLICT (DUPLICATE)
    // =================================================================

    Given("an existing ISP account") {
        When("creating another with the same provider and accountNumber") {
            Then("returns 409 Conflict") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()

                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val accNum = "DUP-ISP-${System.nanoTime()}"
                    val body =
                        """{"accountNumber":"$accNum","circuitId":"CIR-D","providerId":"$providerId","storeId":"$storeId","rate":"500.00","installationDate":"2026-07-10","subscriptionProofId":"$proofId"}"""

                    val first = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    first.status shouldBe HttpStatusCode.Created

                    // Second with same provider + accountNumber → conflict
                    val proofId2 = uploadAttachment(admin.token)
                    val second = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$accNum","circuitId":"CIR-D2","providerId":"$providerId","storeId":"$storeId","rate":"600.00","installationDate":"2026-07-10","subscriptionProofId":"$proofId2"}""",
                        )
                    }
                    second.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  EDGE CASES
    // =================================================================

    Given("input with whitespace") {
        When("accountNumber has leading/trailing spaces") {
            Then("returns 201 with trimmed accountNumber") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()

                    val providerId = createProvider(admin.token)
                    val storeId = createStore(admin.token)
                    val proofId = uploadAttachment(admin.token)

                    val raw = "  WS-ISP-${System.nanoTime()}  "
                    val res = client.post("/accounts/isp") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$raw","circuitId":" CIR-WS ","providerId":"$providerId","storeId":"$storeId","rate":"750.00","installationDate":"2026-07-10","subscriptionProofId":"$proofId"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.Created
                    val d = res.bodyAsText().asJson().data()
                    d.str("accountNumber") shouldBe raw.trim()
                    d.str("circuitId") shouldBe "CIR-WS"
                }
            }
        }
    }
})
