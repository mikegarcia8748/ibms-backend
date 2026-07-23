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
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.*

/**
 * End-to-end integration spec for the two-phase TopSheet compilation flow (DRAFT →
 * edit lines → CONFIRM), against the real composition root and Testcontainers Postgres.
 *
 * Proves the full HTTP lifecycle: preview, draft creation, line editing (RFP + amount),
 * validation rejections, line removal, and final confirmation that mints the invoice
 * number. Also pins the invariants: future periods are rejected, RFP-less lines block
 * confirmation, and DRAFT lines do not count as "already billed".
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
        When("walking the full two-phase draft flow") {
            Then("preview → draft → edit → confirm all work end-to-end") {
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

                    suspend fun createAccount(num: String, storeId: String) =
                        client.post("/accounts") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
                            )
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

                    // 2. Draft — 201, DRAFT status, batch number present
                    val draft = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }
                    draft.status shouldBe HttpStatusCode.Created
                    val draftData = draft.bodyAsText().asJson().data()
                    draftData.str("status") shouldBe "draft"
                    val batchNumber = draftData.str("batchNumber")
                    batchNumber.startsWith("CONV-") shouldBe true
                    batchNumber.endsWith("-B001") shouldBe true
                    val draftId = draftData.str("id")

                    // 3. Lines — the server returns them already sorted by rfpSortOrder
                    //    (branchCode DESC); no client-side re-sort. rfpSortOrder 1/2/3, null rfpNumber.
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
                    serverLines[1]["rfpNumber"] shouldBe JsonNull
                    serverLines[2].str("branchCode").startsWith("050") shouldBe true
                    serverLines[2].intOrNull("rfpSortOrder") shouldBe 3
                    serverLines[2]["rfpNumber"] shouldBe JsonNull

                    val line1Id = serverLines[0].str("id")
                    val line2Id = serverLines[1].str("id")
                    val line3Id = serverLines[2].str("id")

                    // 4. Patch RFP number on line 1 → 200
                    val patch1 = client.patch("/topsheets/$draftId/lines/$line1Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"rfpNumber":"010001"}""")
                    }
                    patch1.status shouldBe HttpStatusCode.OK
                    patch1.bodyAsText().asJson().data().str("rfpNumber") shouldBe "010001"

                    // 5. Patch amount + RFP on line 2 → 200
                    val patch2 = client.patch("/topsheets/$draftId/lines/$line2Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"rfpNumber":"010002","proratedAmount":"950.00"}""")
                    }
                    patch2.status shouldBe HttpStatusCode.OK
                    val patch2Data = patch2.bodyAsText().asJson().data()
                    patch2Data.str("rfpNumber") shouldBe "010002"
                    patch2Data.str("proratedAmount") shouldBe "950.00"

                    // 6. Non-numeric RFP → 400
                    val patchBad = client.patch("/topsheets/$draftId/lines/$line3Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"rfpNumber":"abc"}""")
                    }
                    patchBad.status shouldBe HttpStatusCode.BadRequest

                    // 7. Delete line 3 → 204
                    val delete = client.delete("/topsheets/$draftId/lines/$line3Id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    delete.status shouldBe HttpStatusCode.NoContent

                    // 8. Confirm → 200, COMPILED, invoice number present
                    val confirm = client.post("/topsheets/$draftId/confirm") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    confirm.status shouldBe HttpStatusCode.OK
                    val confirmData = confirm.bodyAsText().asJson().data()
                    confirmData.str("status") shouldBe "compiled"
                    val invoiceNumber = confirmData.str("invoiceNumber")
                    invoiceNumber.startsWith("CONV-") shouldBe true

                    // 9. DRAFT lines do NOT count as "already billed" — a draft for a
                    //    different provider in the same period succeeds.
                    val provider2Id = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Globe-$s","paymentScheduleDay":10}""")
                    }.bodyAsText().asJson().data().str("id")

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"g-$s-1","providerId":"$provider2Id","storeId":"$store118Id","rate":"500","installationDate":"2020-01-01"}""",
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

    Given("a draft whose lines are missing RFP numbers") {
        When("POSTing /topsheets/{id}/confirm") {
            Then("it is rejected with 400") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"NoRfpProv-$s","paymentScheduleDay":5}""")
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
                            """{"storeType":"puregold","branchCode":"NR-$s","name":"NoRfp Store","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"nr-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
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
                    confirm.status shouldBe HttpStatusCode.BadRequest
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

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"id-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
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

                    client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"dp-$s-1","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
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

    Given("a DRAFT topsheet with two stores, one shared by two accounts") {
        When("bulk-assigning an RFP range via /assign-rfp") {
            Then("the highest store code claims startRfpNumber and shared codes share a number") {
                testApplication {
                    application { testModule() }

                    val token = signIn(UserRole.SYSADMIN).token
                    val s = System.nanoTime().toString()

                    val providerId = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"RfpProv-$s","paymentScheduleDay":15}""")
                    }.bodyAsText().asJson().data().str("id")

                    val attachmentId = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"p.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }.bodyAsText().asJson().data().str("attachmentId")

                    suspend fun createStore(code: String) = client.post("/stores") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"storeType":"puregold","branchCode":"$code","name":"Store $code","proofOfInstallationId":"$attachmentId"}""",
                        )
                    }.bodyAsText().asJson().data().str("id")

                    // "220…" sorts above "210…" lexicographically, so 220 is the top (highest) code.
                    val storeHigh = createStore("220-$s")
                    val storeLow = createStore("210-$s")

                    suspend fun createAccount(num: String, storeId: String) = client.post("/accounts") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$num","providerId":"$providerId","storeId":"$storeId","rate":"1000","installationDate":"2020-01-01"}""",
                        )
                    }
                    createAccount("r-$s-1", storeHigh)
                    createAccount("r-$s-2", storeHigh)
                    createAccount("r-$s-3", storeLow)

                    val draftId = client.post("/topsheets/draft") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"providerId":"$providerId","billingPeriod":"$currentPeriod"}""")
                    }.bodyAsText().asJson().data().str("id")

                    // Range covers 3 numbers but there are only 2 distinct store codes -> 400.
                    val mismatch = client.post("/topsheets/$draftId/assign-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"startRfpNumber":"0100021","endRfpNumber":"0100023"}""")
                    }
                    mismatch.status shouldBe HttpStatusCode.BadRequest

                    // Correct range: 2 numbers for 2 distinct store codes.
                    val assign = client.post("/topsheets/$draftId/assign-rfp") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"startRfpNumber":"0100021","endRfpNumber":"0100022"}""")
                    }
                    assign.status shouldBe HttpStatusCode.OK
                    val assigned = assign.bodyAsText().asJson().dataArr().map { it.jsonObject }
                    assigned.size shouldBe 3
                    // Display order (rfpSortOrder): the two 220 accounts first (sharing 0100021), then 210.
                    assigned[0].str("branchCode").startsWith("220") shouldBe true
                    assigned[0].str("rfpNumber") shouldBe "0100021"
                    assigned[1].str("branchCode").startsWith("220") shouldBe true
                    assigned[1].str("rfpNumber") shouldBe "0100021"
                    assigned[2].str("branchCode").startsWith("210") shouldBe true
                    assigned[2].str("rfpNumber") shouldBe "0100022"
                }
            }
        }
    }
})
