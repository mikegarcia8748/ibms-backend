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
 * End-to-end spec for the Account Change Request workflow: secretary submits,
 * manager approves/rejects, secretary cancels. Exercises the real composition root
 * against Testcontainers Postgres, covering happy paths, role enforcement,
 * validation, state-transition guards, and the optional proof attachment.
 */
class AccountChangeRequestSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.strOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.content

    data class AccountSetup(
        val accountId: String,
        val providerId: String,
        val storeId: String,
        val accountNumber: String,
    )

    /** Create a subscription-proof attachment via the presign flow; returns the attachment id. */
    suspend fun ApplicationTestBuilder.createAttachment(token: String): String {
        val res = client.post("/attachments/presign/upload") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"subscription_proof"}""")
        }
        res.status shouldBe HttpStatusCode.OK
        return res.bodyAsText().asJson().data().str("attachmentId")
    }

    /** Full prerequisite chain: provider → attachment → store → active account. */
    suspend fun ApplicationTestBuilder.setupActiveAccount(token: String): AccountSetup {
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
            setBody(
                """{"storeType":"puregold","branchCode":"BC-${System.nanoTime()}","name":"Store","proofOfInstallationId":"$installProof"}""",
            )
        }
        store.status shouldBe HttpStatusCode.Created
        val storeId = store.bodyAsText().asJson().data().str("id")

        val accNum = "ACC-${System.nanoTime()}"
        val acc = client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"accountNumber":"$accNum","providerId":"$providerId","storeId":"$storeId","rate":"1000.00","installationDate":"2025-01-01","planName":"Basic","circuitId":"CIR-001"}""",
            )
        }
        acc.status shouldBe HttpStatusCode.Created
        val accountId = acc.bodyAsText().asJson().data().str("id")
        return AccountSetup(accountId, providerId, storeId, accNum)
    }

    /** Create a second account sharing an existing provider/store. */
    suspend fun ApplicationTestBuilder.createSecondAccount(
        token: String,
        providerId: String,
        storeId: String,
        accountNumber: String,
    ): String {
        val acc = client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"accountNumber":"$accountNumber","providerId":"$providerId","storeId":"$storeId","rate":"1500.00","installationDate":"2025-02-01"}""",
            )
        }
        acc.status shouldBe HttpStatusCode.Created
        return acc.bodyAsText().asJson().data().str("id")
    }

    // =================================================================
    //  HAPPY PATHS
    // =================================================================

    Given("a secretary submitting a change request") {
        When("the request has valid delta fields") {
            Then("it returns 201 with a pending request and correct delta fields") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"NEW-001","rate":"2000.00","circuitId":"CIR-NEW","planName":"Premium"}""",
                        )
                    }
                    res.status shouldBe HttpStatusCode.Created
                    val d = res.bodyAsText().asJson().data()
                    d.str("status") shouldBe "pending"
                    d.str("accountId") shouldBe setup.accountId
                    d.str("submittedById") shouldBe sec.userId
                    d.str("accountNumberNew") shouldBe "NEW-001"
                    d.str("rateNew") shouldBe "2000.00"
                    d.str("circuitIdNew") shouldBe "CIR-NEW"
                    d.str("planNameNew") shouldBe "Premium"
                    d.strOrNull("installationDateNew") shouldBe null
                    d.strOrNull("providerIdNew") shouldBe null
                }
            }
        }

        When("a prior pending request already exists") {
            Then("it auto-cancels the prior and returns a new pending request") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val first = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"FIRST-001"}""")
                    }
                    first.status shouldBe HttpStatusCode.Created
                    val firstId = first.bodyAsText().asJson().data().str("id")

                    val second = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"SECOND-001"}""")
                    }
                    second.status shouldBe HttpStatusCode.Created
                    val secondData = second.bodyAsText().asJson().data()
                    secondData.str("status") shouldBe "pending"
                    secondData.str("accountNumberNew") shouldBe "SECOND-001"
                    secondData.str("id") shouldNotBe firstId

                    // The first request was auto-cancelled.
                    val firstGet = client.get("/accounts/${setup.accountId}/change-requests/$firstId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    firstGet.status shouldBe HttpStatusCode.OK
                    firstGet.bodyAsText().asJson().data()["request"]!!.jsonObject.str("status") shouldBe "cancelled"
                }
            }
        }
    }

    Given("a manager approving a pending change request") {
        When("the request is pending") {
            Then("it returns 200, status=approved, and the account fields are actually updated") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"APPROVED-NUM","rate":"2000.00","planName":"Premium"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val approve = client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }
                    approve.status shouldBe HttpStatusCode.OK
                    val approved = approve.bodyAsText().asJson().data()
                    approved.str("status") shouldBe "approved"
                    approved.strOrNull("approvedById") shouldBe mgr.userId

                    // Account fields were actually persisted.
                    val acc = client.get("/accounts/${setup.accountId}") {
                        header(HttpHeaders.Authorization, "Bearer ${admin.token}")
                    }
                    acc.status shouldBe HttpStatusCode.OK
                    val accData = acc.bodyAsText().asJson().data()
                    accData.str("accountNumber") shouldBe "APPROVED-NUM"
                    accData.str("rate") shouldBe "2000.00"
                    accData.str("planName") shouldBe "Premium"
                }
            }
        }
    }

    Given("a manager rejecting a pending change request") {
        When("a reason is provided") {
            Then("it returns 200, status=rejected, and the reason is stored") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"REJECT-ME"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val reject = client.post("/accounts/${setup.accountId}/change-requests/$reqId/reject") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"Not applicable for this account"}""")
                    }
                    reject.status shouldBe HttpStatusCode.OK
                    val rejected = reject.bodyAsText().asJson().data()
                    rejected.str("status") shouldBe "rejected"
                    rejected.str("rejectedReason") shouldBe "Not applicable for this account"
                }
            }
        }
    }

    Given("a secretary cancelling their own pending request") {
        When("the request belongs to them and is pending") {
            Then("it returns 200 and status=cancelled") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"CANCEL-ME"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val cancel = client.post("/accounts/${setup.accountId}/change-requests/$reqId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    cancel.status shouldBe HttpStatusCode.OK
                    cancel.bodyAsText().asJson().data().str("status") shouldBe "cancelled"
                }
            }
        }
    }

    Given("the diff endpoint") {
        When("GETting a single request with diff") {
            Then("it shows correct current vs proposed values") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"DIFF-NUM","rate":"2000.00"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val diff = client.get("/accounts/${setup.accountId}/change-requests/$reqId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    diff.status shouldBe HttpStatusCode.OK
                    val dd = diff.bodyAsText().asJson().data()
                    dd["request"]!!.jsonObject.str("id") shouldBe reqId
                    val diffs = dd["diff"]!!.jsonArray
                    diffs.size shouldBe 2
                    val numDiff = diffs.first { it.jsonObject.str("field") == "accountNumber" }.jsonObject
                    numDiff.str("currentValue") shouldBe setup.accountNumber
                    numDiff.str("proposedValue") shouldBe "DIFF-NUM"
                    val rateDiff = diffs.first { it.jsonObject.str("field") == "rate" }.jsonObject
                    rateDiff.str("currentValue") shouldBe "1000.00"
                    rateDiff.str("proposedValue") shouldBe "2000.00"
                }
            }
        }
    }

    Given("the list endpoint") {
        When("GETting change requests with pagination") {
            Then("it returns an enveloped cursor page") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"LIST-1"}""")
                    }

                    val list = client.get("/accounts/${setup.accountId}/change-requests?limit=10") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    list.status shouldBe HttpStatusCode.OK
                    val ld = list.bodyAsText().asJson().data()
                    (ld["items"] is JsonArray) shouldBe true
                    ld["items"]!!.jsonArray.isNotEmpty() shouldBe true
                    ld.containsKey("nextCursor") shouldBe true
                }
            }
        }

        When("filtering by status") {
            Then("only matching requests are returned") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    // Submit then approve so we have one of each.
                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"FILTER-1"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")
                    client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }

                    // Approved filter should include it.
                    val approved = client.get("/accounts/${setup.accountId}/change-requests?status=approved") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    approved.status shouldBe HttpStatusCode.OK
                    val approvedItems = approved.bodyAsText().asJson().data()["items"]!!.jsonArray
                    approvedItems.forEach { it.jsonObject.str("status") shouldBe "approved" }

                    // Pending filter should be empty.
                    val pending = client.get("/accounts/${setup.accountId}/change-requests?status=pending") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    pending.status shouldBe HttpStatusCode.OK
                    pending.bodyAsText().asJson().data()["items"]!!.jsonArray.isEmpty() shouldBe true
                }
            }
        }
    }

    // =================================================================
    //  ROLE ENFORCEMENT
    // =================================================================

    Given("a manager attempting to submit a change request") {
        When("a non-secretary calls POST submit") {
            Then("it returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"NOPE"}""")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    Given("a secretary attempting to approve") {
        When("a non-manager calls POST approve") {
            Then("it returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"TO-APPROVE"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    Given("a secretary attempting to reject") {
        When("a non-manager calls POST reject") {
            Then("it returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"TO-REJECT"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/reject") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"no"}""")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    Given("a secretary attempting to cancel another secretary's request") {
        When("the canceller is not the submitter") {
            Then("it returns 403 Forbidden") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val secA = signIn(UserRole.SECRETARY)
                    val secB = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${secA.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"SEC-A-REQ"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer ${secB.token}")
                    }
                    res.status shouldBe HttpStatusCode.Forbidden
                }
            }
        }
    }

    // =================================================================
    //  VALIDATION & EDGE CASES
    // =================================================================

    Given("a secretary submitting with no changed fields") {
        When("the body has no delta fields") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("a secretary submitting with an invalid providerId") {
        When("the provider does not exist") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"00000000-0000-0000-0000-000000000000"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("a secretary submitting with a non-positive rate") {
        When("the rate is zero") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"rate":"0"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }

        When("the rate is negative") {
            Then("it returns 400") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"rate":"-100"}""")
                    }
                    res.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("state-transition guards on already-processed requests") {
        When("approving an already-approved request") {
            Then("it returns 409") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"DONE"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")
                    client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        When("rejecting an already-approved request") {
            Then("it returns 409") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"DONE2"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")
                    client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/reject") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"too late"}""")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        When("cancelling an already-cancelled request") {
            Then("it returns 409") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"CANCEL-TWICE"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")
                    client.post("/accounts/${setup.accountId}/change-requests/$reqId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    Given("submitting for a non-existent account") {
        When("the account id does not exist") {
            Then("it returns 404") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)

                    val res = client.post("/accounts/00000000-0000-0000-0000-000000000000/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"GHOST"}""")
                    }
                    res.status shouldBe HttpStatusCode.NotFound
                }
            }
        }
    }

    Given("submitting for a non-active account") {
        When("the account has been deactivated (termination_requested)") {
            Then("it returns 409") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)
                    val deactivationProof = createAttachment(admin.token)

                    client.post("/accounts/${setup.accountId}/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$deactivationProof"}""")
                    }

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"AFTER-DEACT"}""")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    Given("approving with an account number conflict") {
        When("the new account number already exists for the same provider") {
            Then("it returns 409") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val mgr = signIn(UserRole.MANAGER)
                    val setup = setupActiveAccount(admin.token)

                    // A second account sharing the same provider.
                    val conflictingNumber = "CONFLICT-${System.nanoTime()}"
                    createSecondAccount(admin.token, setup.providerId, setup.storeId, conflictingNumber)

                    // Submit a change request on the first account to take the second's number.
                    val submit = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"$conflictingNumber"}""")
                    }
                    val reqId = submit.bodyAsText().asJson().data().str("id")

                    val res = client.post("/accounts/${setup.accountId}/change-requests/$reqId/approve") {
                        header(HttpHeaders.Authorization, "Bearer ${mgr.token}")
                    }
                    res.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  PROOF ATTACHMENT (OPTIONAL)
    // =================================================================

    Given("a secretary submitting with a proof attachment") {
        When("proofAttachmentId references a valid attachment") {
            Then("it returns 201 with the proof stored") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)
                    val proofId = createAttachment(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"WITH-PROOF","proofAttachmentId":"$proofId"}""")
                    }
                    res.status shouldBe HttpStatusCode.Created
                    res.bodyAsText().asJson().data().str("proofAttachmentId") shouldBe proofId
                }
            }
        }

        When("proofAttachmentId is omitted") {
            Then("it returns 201 with proofAttachmentId null") {
                testApplication {
                    application { testModule() }
                    val admin = signIn()
                    val sec = signIn(UserRole.SECRETARY)
                    val setup = setupActiveAccount(admin.token)

                    val res = client.post("/accounts/${setup.accountId}/change-requests") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"accountNumber":"NO-PROOF"}""")
                    }
                    res.status shouldBe HttpStatusCode.Created
                    res.bodyAsText().asJson().data().strOrNull("proofAttachmentId") shouldBe null
                }
            }
        }
    }
})
