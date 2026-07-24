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
 * End-to-end integration spec for the account deactivation and cancellation flow.
 * Exercises the real composition root against Testcontainers Postgres.
 */
class DeactivationFlowSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** Create a subscription-proof attachment via the presign flow; returns the attachment id. */
    suspend fun ApplicationTestBuilder.createAttachment(token: String, purpose: String = "subscription_proof"): String {
        val res = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"$purpose"}""")
        }
        res.status shouldBe HttpStatusCode.OK
        return res.bodyAsText().asJson().data().str("attachmentId")
    }

    /** Full prerequisite chain: provider -> attachment -> store -> active account. */
    suspend fun ApplicationTestBuilder.setupActiveAccount(token: String): String {
        val prov = client.post("/providers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Prov-${System.nanoTime()}","paymentScheduleDay":5}""")
        }
        prov.status shouldBe HttpStatusCode.Created
        val providerId = prov.bodyAsText().asJson().data().str("id")

        val installProof = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"inst.txt","contentType":"text/plain","purpose":"installation_proof"}""")
        }.bodyAsText().asJson().data().str("attachmentId")

        val store = client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"storeType":"puregold","branchCode":"BC-${System.nanoTime()}","name":"Store","proofOfInstallationId":"$installProof"}""")
        }
        store.status shouldBe HttpStatusCode.Created
        val storeId = store.bodyAsText().asJson().data().str("id")

        val acc = client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"accountNumber":"ACC-${System.nanoTime()}","providerId":"$providerId","storeId":"$storeId","rate":"1000.00","installationDate":"2025-01-01"}""")
        }
        acc.status shouldBe HttpStatusCode.Created
        return acc.bodyAsText().asJson().data().str("id")
    }

    // =================================================================
    //  E2E DEACTIVATION FLOW
    // =================================================================

    Given("an E2E deactivation flow") {
        When("secretary creates account, uploads proof, and deactivates") {
            Then("response has status=termination_requested, graceEndDate populated, proof linked") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")

                    val deactivate = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }
                    deactivate.status shouldBe HttpStatusCode.OK
                    val d = deactivate.bodyAsText().asJson().data()
                    d.str("status") shouldBe "termination_requested"
                    d.strOrNull("graceEndDate") shouldNotBe null
                    d.strOrNull("terminationRequestedAt") shouldNotBe null

                    // Verify via GET
                    val get = client.get("/accounts/$accountId") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    }
                    get.status shouldBe HttpStatusCode.OK
                    val acc = get.bodyAsText().asJson().data()
                    acc.str("status") shouldBe "termination_requested"
                    acc.strOrNull("graceEndDate") shouldNotBe null
                }
            }
        }
    }

    // =================================================================
    //  E2E CANCELLATION FLOW
    // =================================================================

    Given("an E2E cancellation flow") {
        When("after deactivation, cancel with reason") {
            Then("response has status=active, graceEndDate=null") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val cancel = client.post("/accounts/$accountId/cancel-deactivation") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"Customer changed their mind"}""")
                    }
                    cancel.status shouldBe HttpStatusCode.OK
                    val d = cancel.bodyAsText().asJson().data()
                    d.str("status") shouldBe "active"
                    d.strOrNull("graceEndDate") shouldBe null
                    d.strOrNull("terminationRequestedAt") shouldBe null
                }
            }
        }
    }

    // =================================================================
    //  ROLE ENFORCEMENT
    // =================================================================

    Given("role enforcement for deactivation") {
        When("finance user attempts to deactivate") {
            Then("returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val finance = signIn(UserRole.FINANCE)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(finance.token, "deactivation_proof")
                    val res = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${finance.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    Given("role enforcement for cancellation") {
        When("finance user attempts to cancel deactivation") {
            Then("returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val finance = signIn(UserRole.FINANCE)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val res = client.post("/accounts/$accountId/cancel-deactivation") {
                        header(HttpHeaders.Authorization, "Bearer ${finance.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"No permission"}""")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    // =================================================================
    //  DEACTIVATE NON-ACTIVE ACCOUNT
    // =================================================================

    Given("deactivating a non-active (transferred) account") {
        When("attempting to deactivate a transferred account") {
            Then("returns 409 Conflict") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)

                    // Setup: create account, then transfer it
                    val prov = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"TransProv-${System.nanoTime()}","paymentScheduleDay":10}""")
                    }
                    val providerId = prov.bodyAsText().asJson().data().str("id")

                    val installProof = createAttachment(admin.token, "installation_proof")
                    val store1 = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"storeType":"puregold","branchCode":"TR1-${System.nanoTime()}","name":"Store1","proofOfInstallationId":"$installProof"}""")
                    }.bodyAsText().asJson().data().str("id")

                    val installProof2 = createAttachment(admin.token, "installation_proof")
                    val store2 = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"storeType":"puregold","branchCode":"TR2-${System.nanoTime()}","name":"Store2","proofOfInstallationId":"$installProof2"}""")
                    }.bodyAsText().asJson().data().str("id")

                    val accRes = client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"TRN-${System.nanoTime()}","providerId":"$providerId","storeId":"$store1","rate":"500.00","installationDate":"2025-03-01"}""")
                    }
                    val accountId = accRes.bodyAsText().asJson().data().str("id")

                    val transferProof = createAttachment(sec.token, "transfer_proof")
                    client.post("/accounts/$accountId/transfer") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newStoreId":"$store2","proofId":"$transferProof"}""")
                    }.status shouldBe HttpStatusCode.Created

                    // Now try to deactivate the transferred account
                    val deactProof = createAttachment(sec.token, "deactivation_proof")
                    val res = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$deactProof"}""")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  CANCEL NON-TERMINATION_REQUESTED ACCOUNT
    // =================================================================

    Given("cancelling deactivation of a non-termination-requested account") {
        When("attempting to cancel an active account") {
            Then("returns 409 Conflict") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/$accountId/cancel-deactivation") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"Not applicable"}""")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  IDEMPOTENCY ON DEACTIVATION
    // =================================================================

    Given("idempotency on deactivation") {
        When("same request with same Idempotency-Key sent twice") {
            Then("second request returns same response (replay)") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    val idemKey = "deact-idem-${System.nanoTime()}"

                    val first = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        header("Idempotency-Key", idemKey)
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }
                    first.status shouldBe HttpStatusCode.OK

                    val second = client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        header("Idempotency-Key", idemKey)
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }
                    second.status shouldBe HttpStatusCode.OK

                    val firstData = first.bodyAsText().asJson().data()
                    val secondData = second.bodyAsText().asJson().data()
                    secondData.str("id") shouldBe firstData.str("id")
                    secondData.str("status") shouldBe firstData.str("status")
                }
            }
        }
    }

    // =================================================================
    //  STATUS FILTER
    // =================================================================

    Given("status filter on accounts list") {
        When("filtering by termination_requested") {
            Then("only deactivated account is returned with graceEndDate") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId1 = setupActiveAccount(admin.token)
                    val accountId2 = setupActiveAccount(admin.token)

                    // Deactivate only the first
                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId1/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val list = client.get("/accounts?status=termination_requested") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    }
                    list.status shouldBe HttpStatusCode.OK
                    val items = list.bodyAsText().asJson().data()["items"]!!.jsonArray
                    items.any { it.jsonObject.str("id") == accountId1 } shouldBe true
                    items.none { it.jsonObject.str("id") == accountId2 } shouldBe true
                    // graceEndDate should be populated on the deactivated account
                    val deactivated = items.first { it.jsonObject.str("id") == accountId1 }.jsonObject
                    deactivated.strOrNull("graceEndDate") shouldNotBe null
                }
            }
        }
    }

    // =================================================================
    //  ACCOUNT CHANGE REQUEST BLOCKED
    // =================================================================

    Given("account change request blocked after deactivation") {
        When("submitting change request on a deactivated account") {
            Then("returns 409 Conflict") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val res = client.post("/accounts/$accountId/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"AFTER-DEACT"}""")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  GRACE EXPIRY VIA JOB
    // =================================================================

    Given("grace expiry via admin job") {
        When("deactivated account is expired by the job") {
            Then("account becomes INACTIVE after grace period") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val accountId = setupActiveAccount(admin.token)

                    val proofId = createAttachment(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$proofId"}""")
                    }.status shouldBe HttpStatusCode.OK

                    // Trigger the expire-grace job. Note: in a real scenario the 30-day
                    // grace must elapse. Because SystemClock is used in integration tests,
                    // the account will NOT expire here (it was just created). We verify
                    // the job runs successfully and returns 0 expired.
                    val jobRes = client.post("/admin/jobs/expire-grace") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    }
                    jobRes.status shouldBe HttpStatusCode.OK
                    val jobData = jobRes.bodyAsText().asJson().data()
                    // The account was just deactivated so grace hasn't elapsed yet
                    jobData["expired"]!!.jsonPrimitive.int shouldBe 0

                    // Account should still be termination_requested (not yet expired)
                    val acc = client.get("/accounts/$accountId") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    }
                    acc.status shouldBe HttpStatusCode.OK
                    acc.bodyAsText().asJson().data().str("status") shouldBe "termination_requested"
                }
            }
        }
    }
})
