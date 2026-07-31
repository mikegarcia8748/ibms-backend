package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/**
 * End-to-end integration spec for the TopSheet lifecycle (DRAFT → edit amounts →
 * CONFIRM → generate-rfp → release-to-finance), against the real composition root and
 * Testcontainers Postgres.
 *
 * Proves the full HTTP lifecycle: preview, draft creation, prorated-amount editing,
 * line removal, confirmation that mints the invoice number, external RFP generation
 * (per line, via the simulated gateway), and the secretary's release-to-finance handoff.
 * Also pins the invariants: future periods are rejected, RFP is assigned only after
 * confirm, the state-machine guards hold, and DRAFT lines do not count as "already billed".
 */
class TopSheetDraftFlowSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.dataArr(): JsonArray = this["data"]!!.jsonArray
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.int

    // Compute the current and a future billing period from the real wall clock so the
    // spec is deterministic regardless of when it runs.
    val now = Clock.System.now().toLocalDateTime(TimeZone.of("Asia/Manila"))
    val currentPeriod = "${now.year}-${now.monthNumber.toString().padStart(2, '0')}"
    val futurePeriod = if (now.monthNumber == 12) {
        "${now.year + 1}-01"
    } else {
        "${now.year}-${(now.monthNumber + 1).toString().padStart(2, '0')}"
    }

    Given("seeded provider, stores, and accounts") {
        When("walking the full lifecycle") {
            Then("preview → draft → edit → confirm → generate-rfp → release-to-finance all work") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    // --- seed: provider, attachment (store proof), 2 stores, 3 accounts ---
                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Converge-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val store118Id = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"118-$s","name":"Store 118","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val store050Id = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"050-$s","name":"Store 050","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    suspend fun createAccount(num: String, storeId: String): HttpResponse {
                        val proofId1 = uploadPdfProof(token, "subscription_proof")
                        return client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId1"]}""",
                            )
                        }
                    }

                    createAccount("acc-$s-1", store118Id)
                    createAccount("acc-$s-2", store118Id)
                    createAccount("acc-$s-3", store050Id)

                    // 1. Preview — 3 eligible accounts, total 3000.00
                    val preview = client.post("/topsheets/preview") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }
                    preview.status shouldBe HttpStatusCode.OK
                    val previewData = preview.bodyAsText().asJson().data()
                    previewData["lines"]!!.jsonArray.size shouldBe 3
                    previewData.str("totalAmount") shouldBe "3000.00"

                    // 2. Draft — 201, DRAFT status, no invoice/batch number yet (minted at confirm)
                    val draft = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }
                    draft.status shouldBe HttpStatusCode.Created
                    val draftData = draft.bodyAsText().asJson().data()
                    draftData.str("status") shouldBe "draft"
                    draftData["batchNumber"] shouldBe JsonNull
                    draftData["invoiceNumber"] shouldBe JsonNull
                    val draftId = draftData.str("id")

                    // 3. Lines — server returns them sorted by rfpSortOrder (branchCode DESC),
                    //    with null rfpNumber (RFP is assigned by the external system after confirm).
                    val linesResp = client.get("/topsheets/$draftId/lines") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    linesResp.status shouldBe HttpStatusCode.OK
                    val serverLines = linesResp.bodyAsText().asJson().dataArr().map { it.jsonObject }
                    serverLines.size shouldBe 3
                    serverLines[0].str("branchCode").startsWith("118") shouldBe true
                    serverLines[0].intOrNull("rfpSortOrder") shouldBe 1
                    serverLines[0]["rfpNumber"] shouldBe JsonNull
                    serverLines[1].str("branchCode").startsWith("118") shouldBe true
                    serverLines[1].intOrNull("rfpSortOrder") shouldBe 2
                    serverLines[2].str("branchCode").startsWith("050") shouldBe true
                    serverLines[2].intOrNull("rfpSortOrder") shouldBe 3

                    val line2Id = serverLines[1].str("id")
                    val line3Id = serverLines[2].str("id")

                    // 4. Patch prorated amount on line 2 → 200
                    val patch = client.patch("/topsheets/$draftId/lines/$line2Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proratedAmount":"950.00"}""")
                    }
                    patch.status shouldBe HttpStatusCode.OK
                    patch.bodyAsText().asJson().data().str("proratedAmount") shouldBe "950.00"

                    // 5. Patch with no editable field → 400
                    val patchEmpty = client.patch("/topsheets/$draftId/lines/$line2Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{}""")
                    }
                    patchEmpty.status shouldBe HttpStatusCode.BadRequest

                    // 6. Delete line 3 → 204
                    val delete = client.delete("/topsheets/$draftId/lines/$line3Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    delete.status shouldBe HttpStatusCode.NoContent

                    // 7. Confirm → 200, COMPILED, invoice number present (no RFP required yet)
                    val confirm = client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    confirm.status shouldBe HttpStatusCode.OK
                    val confirmData = confirm.bodyAsText().asJson().data()
                    confirmData.str("status") shouldBe "compiled"
                    confirmData.str("invoiceNumber").startsWith("CONV-") shouldBe true
                    // Batch number is minted here, at confirm (null during DRAFT).
                    val batchNumber = confirmData.str("batchNumber")
                    batchNumber.startsWith("CONV-") shouldBe true
                    batchNumber.endsWith("-B001") shouldBe true

                    // 8. Generate RFP via the external (simulated) system → 200, every line
                    //    now carries an rfpNumber + rfpUniqueKey; topsheet moves to rfp_assigned.
                    val generate = client.post("/topsheets/$draftId/generate-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    generate.status shouldBe HttpStatusCode.OK
                    val rfpLines = generate.bodyAsText().asJson().dataArr().map { it.jsonObject }
                    rfpLines.size shouldBe 2
                    rfpLines.forEach { line ->
                        (line["rfpNumber"] is JsonNull) shouldBe false
                        (line["rfpUniqueKey"] is JsonNull) shouldBe false
                    }
                    rfpLines[0].str("rfpNumber") shouldBe "0100001"

                    client.get("/topsheets/$draftId") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText().asJson().data().str("status") shouldBe "rfp_assigned"

                    // 9. Release to finance → 200, status approved
                    val release = client.post("/topsheets/$draftId/release-to-finance") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    release.status shouldBe HttpStatusCode.OK
                    release.bodyAsText().asJson().data().str("status") shouldBe "approved"

                    // 10. DRAFT lines do NOT count as "already billed" — a draft for a
                    //     different provider in the same period succeeds.
                    val provider2Id = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Globe-$s","paymentScheduleDay":10}""")
                    }.bodyAsText().asJson().data().str("id")

                    val proofId2 = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"g-$s-1","providerId":"$provider2Id","storeId":"$store118Id","rate":"500","installationDate":"2020-01-01","subscriptionProofIds":["$proofId2"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val draft2 = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$provider2Id","billingPeriod":"$currentPeriod"}""")
                    }
                    draft2.status shouldBe HttpStatusCode.Created
                    draft2.bodyAsText().asJson().data().str("status") shouldBe "draft"
                }
            }
        }
    }

    Given("a future billing period") {
        When("POSTing /topsheets/draft") {
            Then("it is rejected with 400") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"FutProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val resp = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$futurePeriod"}""")
                    }
                    resp.status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
    }

    Given("a draft that is confirmed before any RFP is assigned") {
        When("walking the RFP/release state-machine guards") {
            Then("confirm succeeds without RFP; generate-rfp and release enforce their statuses") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"GuardProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"GD-$s","name":"Guard Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val proofId3 = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"gd-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId3"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    // generate-rfp on a DRAFT (not yet compiled) → 409
                    client.post("/topsheets/$draftId/generate-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.Conflict

                    // Confirm now succeeds even though no line has an RFP number → COMPILED.
                    val confirm = client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    confirm.status shouldBe HttpStatusCode.OK
                    confirm.bodyAsText().asJson().data().str("status") shouldBe "compiled"

                    // release-to-finance before generate-rfp → 409 (needs rfp_assigned)
                    client.post("/topsheets/$draftId/release-to-finance") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.Conflict

                    // generate-rfp → 200 rfp_assigned, then release → 200 approved.
                    client.post("/topsheets/$draftId/generate-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    client.post("/topsheets/$draftId/release-to-finance") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText().asJson().data().str("status") shouldBe "approved"
                }
            }
        }
    }

    Given("an existing draft for a provider/period") {
        When("POSTing /topsheets/draft again with the same Idempotency-Key") {
            Then("it replays the stored result (201 with the same draft id)") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"IdemProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"ID-$s","name":"Idem Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val proofId4 = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"id-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId4"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val body = """{"providerId":"$providerId","billingPeriod":"$currentPeriod"}"""

                    val first = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header("Idempotency-Key", "dup-key-$s")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    first.status shouldBe HttpStatusCode.Created
                    val firstId = first.bodyAsText().asJson().data().str("id")

                    val second = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header("Idempotency-Key", "dup-key-$s")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    second.status shouldBe HttpStatusCode.Created
                    second.bodyAsText().asJson().data().str("id") shouldBe firstId
                }
            }
        }
    }

    Given("an existing DRAFT and a second draft attempt without an Idempotency-Key") {
        When("POSTing /topsheets/draft again for the same provider/period") {
            Then("it is rejected with 409 (not a raw 500 from the unique index)") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"DupProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"DP-$s","name":"Dup Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val proofId5 = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"dp-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId5"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val body = """{"providerId":"$providerId","billingPeriod":"$currentPeriod"}"""

                    client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status shouldBe HttpStatusCode.Created

                    val second = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    second.status shouldBe HttpStatusCode.Conflict
                    second.bodyAsText().asJson().str("message") shouldBe
                        "a draft already exists for this provider/period"
                }
            }
        }
    }

    Given("a DRAFT topsheet the secretary wants to discard") {
        When("cancelling it, then drafting the same provider/period again") {
            Then("cancel returns 200 'cancelled', its lines are gone, and a fresh draft succeeds") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"CancProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"CA-$s","name":"Canc Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val proof = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"ca-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proof"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val body = """{"providerId":"$providerId","billingPeriod":"$currentPeriod"}"""
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.bodyAsText().asJson().data().str("id")

                    // Cancel the DRAFT → 200, status 'cancelled'.
                    val cancel = client.post("/topsheets/$draftId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    cancel.status shouldBe HttpStatusCode.OK
                    cancel.bodyAsText().asJson().data().str("status") shouldBe "cancelled"

                    // Its lines are dropped so the account frees up.
                    client.get("/topsheets/$draftId/lines") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.bodyAsText().asJson().dataArr().size shouldBe 0

                    // A fresh draft for the same provider/period now succeeds (block cleared).
                    val redraft = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    redraft.status shouldBe HttpStatusCode.Created
                    redraft.bodyAsText().asJson().data().str("status") shouldBe "draft"
                }
            }
        }
    }

    Given("a COMPILED topsheet, and separately one already at rfp_assigned") {
        When("cancelling each") {
            Then("the COMPILED one voids and re-bills; the rfp_assigned one is rejected 409") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"CmpCancProv-$s","paymentScheduleDay":5}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    val storeId = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"CB-$s","name":"Canc Store B","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    val proof = uploadPdfProof(token, "subscription_proof")
                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"cb-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proof"]}""",
                        )
                    }.status shouldBe HttpStatusCode.Created

                    val body = """{"providerId":"$providerId","billingPeriod":"$currentPeriod"}"""

                    // Draft → confirm → COMPILED, then cancel the COMPILED topsheet → 200 cancelled.
                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.bodyAsText().asJson().data().str("id")
                    client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    val cancelCompiled = client.post("/topsheets/$draftId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    cancelCompiled.status shouldBe HttpStatusCode.OK
                    cancelCompiled.bodyAsText().asJson().data().str("status") shouldBe "cancelled"

                    // The account is re-billable: a fresh draft compiles it again.
                    val redraftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.let {
                        it.status shouldBe HttpStatusCode.Created
                        it.bodyAsText().asJson().data().str("id")
                    }

                    // Push it to rfp_assigned; cancelling then is blocked with 409.
                    client.post("/topsheets/$redraftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    client.post("/topsheets/$redraftId/generate-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.OK
                    client.post("/topsheets/$redraftId/cancel") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }.status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    Given("two providers whose names collapse to the same invoice acronym") {
        When("each confirms a top sheet in the same billing period") {
            // The acronym is the first 4 characters of a single-word provider name, and every
            // provider's invoice sequence starts at 1 — so "Converge" and "Convergys" both mint
            // <ACRONYM>-YYYYMM-0001. Under the pre-V22 global UNIQUE on topsheets.invoice_number
            // the second provider hit 23505 -> 409 and could never bill that period.
            Then("both succeed, even though they mint the same invoice number") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    /** Seed a provider + store + one account, then draft and confirm. */
                    suspend fun confirmFor(providerName: String, tag: String): JsonObject {
                        val providerId = client.post("/providers") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody("""{"name":"$providerName","paymentScheduleDay":15}""")
                        }.bodyAsText().asJson().data().str("id")

                        val storeId = client.post("/stores") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"storeType":"puregold","branchCode":"$tag-$s","name":"Store $tag","proofOfInstallationId":"$attachmentId"}""",
                            )
                        }.bodyAsText().asJson().data().str("id")

                        val proofId = uploadPdfProof(token, "subscription_proof")
                        client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$tag-$s","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01","subscriptionProofIds":["$proofId"]}""",
                            )
                        }.status shouldBe HttpStatusCode.Created

                        val draftId = client.post("/topsheets/draft") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                        }.bodyAsText().asJson().data().str("id")

                        val confirm = client.post("/topsheets/$draftId/confirm") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                        confirm.status shouldBe HttpStatusCode.OK
                        return confirm.bodyAsText().asJson().data()
                    }

                    val first = confirmFor("Converge-A$s", "CVA")
                    val second = confirmFor("Convergys-B$s", "CVB")

                    // Same acronym, same period, each provider's own sequence at 1 — so the two
                    // invoice numbers are byte-identical across different providers.
                    first.str("invoiceNumber") shouldBe second.str("invoiceNumber")
                    first.str("invoiceNumber").startsWith("CONV-") shouldBe true
                    first.str("invoiceNumber").endsWith("-0001") shouldBe true
                }
            }
        }
    }
})
