package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import com.puregoldbe.ibms.support.uploadPdfProof
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Account identity is `(store, provider, account_number, COALESCE(circuit_id, ''))`,
 * unique among LIVE rows only (`uq_account_identity_active`, V16).
 *
 * The model deliberately allows one account number to recur across stores, and one store
 * to hold several circuits under one account number — a single billing account with many
 * ISP lines. These specs pin down both halves: that the allowed shapes really are allowed,
 * and that the edges which used to admit a duplicate (padding, case, a whitespace circuit,
 * a store change smuggled through an update) now do not.
 */
class AccountIdentitySpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun JsonObject.data(): JsonObject = this["data"]!!.jsonObject
    fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content
    fun JsonObject.strOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    suspend fun ApplicationTestBuilder.newProvider(token: String): String =
        client.post("/providers") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"IdProv-${System.nanoTime()}","paymentScheduleDay":5}""")
        }.bodyAsText().asJson().data().str("id")

    suspend fun ApplicationTestBuilder.newStore(token: String, name: String = "Store"): String {
        val installProof = uploadPdfProof(token, "installation_proof")
        return client.post("/stores") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"storeType":"puregold","branchCode":"ID-${System.nanoTime()}","name":"$name",""" +
                    """"proofOfInstallationId":"$installProof"}""",
            )
        }.bodyAsText().asJson().data().str("id")
    }

    /** Raw create so each spec can assert on the status and message itself. */
    suspend fun ApplicationTestBuilder.createAccount(
        token: String,
        providerId: String,
        storeId: String,
        accountNumber: String,
        circuitJson: String,
    ): HttpResponse {
        val proof = uploadPdfProof(token, "subscription_proof")
        return client.post("/accounts") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(
                """{"accountNumber":"$accountNumber","providerId":"$providerId","storeId":"$storeId",""" +
                    """$circuitJson"rate":"1000.00","installationDate":"2025-01-01",""" +
                    """"subscriptionProofIds":["$proof"]}""",
            )
        }
    }

    // =================================================================
    //  THE SHAPES THE MODEL IS MEANT TO ALLOW
    // =================================================================

    Given("one account number used at two different stores") {
        When("both are created with their own circuit") {
            Then("both succeed — store is part of the identity") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val storeA = newStore(sec.token, "Alpha")
                    val storeB = newStore(sec.token, "Bravo")
                    val number = "SHARED-${System.nanoTime()}"

                    createAccount(sec.token, provider, storeA, number, """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Created
                    createAccount(sec.token, provider, storeB, number, """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Created
                }
            }
        }
    }

    Given("one store holding several circuits under one account number") {
        When("the second circuit is added") {
            Then("it succeeds — circuit is part of the identity") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token)
                    val number = "MULTI-${System.nanoTime()}"

                    createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Created
                    createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-2",""")
                        .status shouldBe HttpStatusCode.Created
                }
            }
        }
    }

    // =================================================================
    //  THE EDGES THAT USED TO ADMIT A DUPLICATE
    // =================================================================

    Given("an account number and circuit already live at a store") {
        When("the identical pair is created again") {
            Then("409, and the message names the store and circuit") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token, "Alpha")
                    val number = "DUP-${System.nanoTime()}"

                    createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Created
                    val second = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")

                    second.status shouldBe HttpStatusCode.Conflict
                    val message = second.bodyAsText().asJson().str("message")
                    message shouldContain "circuit CIRC-1"
                    message shouldContain "Alpha"
                }
            }
        }
    }

    Given("an account whose circuit is whitespace only") {
        When("a second account with no circuit at all is created") {
            Then("409 — blank collapses to the no-circuit slot instead of a third one") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token)
                    val number = "BLANK-${System.nanoTime()}"

                    // Written as null, not as two literal spaces: the guard looks in the
                    // no-circuit slot, so the row must land there too.
                    val first = createAccount(sec.token, provider, store, number, """"circuitId":"   ",""")
                    first.status shouldBe HttpStatusCode.Created
                    first.bodyAsText().asJson().data().strOrNull("circuitId") shouldBe null

                    createAccount(sec.token, provider, store, number, "")
                        .status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    Given("an account number that differs only by padding or case") {
        When("the variants are created") {
            Then("both are rejected as the same identity") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token)
                    val number = "case-${System.nanoTime()}"

                    val first = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                    first.status shouldBe HttpStatusCode.Created
                    first.bodyAsText().asJson().data().str("accountNumber") shouldBe number

                    createAccount(sec.token, provider, store, "  $number  ", """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Conflict
                    createAccount(sec.token, provider, store, number.uppercase(), """"circuitId":"CIRC-1",""")
                        .status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    Given("a circuit already live at a store under a padded circuit id") {
        When("the trimmed form is created") {
            Then("409 — the stored circuit was normalized on the way in") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token)
                    val number = "PADCIRC-${System.nanoTime()}"

                    createAccount(sec.token, provider, store, number, """"circuitId":" CIRC-9 ",""")
                        .status shouldBe HttpStatusCode.Created
                    createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-9",""")
                        .status shouldBe HttpStatusCode.Conflict
                }
            }
        }
    }

    // =================================================================
    //  UPDATE MUST NOT BE A BACK DOOR INTO TRANSFER
    // =================================================================

    Given("an existing account and a second store") {
        When("an update tries to move it by rewriting storeId") {
            Then("409 pointing at the transfer endpoint — no silent relocation") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val storeA = newStore(sec.token, "Alpha")
                    val storeB = newStore(sec.token, "Bravo")
                    val number = "MOVE-${System.nanoTime()}"

                    val created = createAccount(sec.token, provider, storeA, number, """"circuitId":"CIRC-1",""")
                    created.status shouldBe HttpStatusCode.Created
                    val accountId = created.bodyAsText().asJson().data().str("id")

                    val moved = client.put("/accounts/$accountId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$number","circuitId":"CIRC-1","providerId":"$provider",""" +
                                """"storeId":"$storeB","rate":"1000.00","installationDate":"2025-01-01"}""",
                        )
                    }
                    moved.status shouldBe HttpStatusCode.Conflict
                    moved.bodyAsText().asJson().str("message") shouldContain "transfer"

                    // And it really did not move.
                    client.get("/accounts/$accountId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                    }.bodyAsText().asJson().data().str("storeId") shouldBe storeA
                }
            }
        }
    }

    Given("two accounts sharing a number at one store under different circuits") {
        When("an update points one of them at the other's circuit") {
            Then("409 rather than an unattributed constraint violation") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token, "Alpha")
                    val number = "COLLIDE-${System.nanoTime()}"

                    val first = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                    first.status shouldBe HttpStatusCode.Created
                    val firstId = first.bodyAsText().asJson().data().str("id")
                    createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-2",""")
                        .status shouldBe HttpStatusCode.Created

                    val collide = client.put("/accounts/$firstId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$number","circuitId":"CIRC-2","providerId":"$provider",""" +
                                """"storeId":"$store","rate":"1000.00","installationDate":"2025-01-01"}""",
                        )
                    }
                    collide.status shouldBe HttpStatusCode.Conflict
                    collide.bodyAsText().asJson().str("message") shouldContain "circuit CIRC-2"
                }
            }
        }
    }

    Given("an account being edited without touching its identity") {
        When("only the rate changes") {
            Then("the update succeeds — the guard must not block a plain edit") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token)
                    val number = "EDIT-${System.nanoTime()}"

                    val created = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                    created.status shouldBe HttpStatusCode.Created
                    val accountId = created.bodyAsText().asJson().data().str("id")

                    val edited = client.put("/accounts/$accountId") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody(
                            """{"accountNumber":"$number","circuitId":"CIRC-1","providerId":"$provider",""" +
                                """"storeId":"$store","rate":"2500.00","installationDate":"2025-01-01"}""",
                        )
                    }
                    edited.status shouldBe HttpStatusCode.OK
                    edited.bodyAsText().asJson().data().str("rate") shouldBe "2500.00"
                }
            }
        }
    }

    // =================================================================
    //  TRANSFER AND THE IDENTITY SLOT
    // =================================================================

    Given("a destination store that has been closed") {
        When("an account is transferred into it") {
            Then("409 — a transfer must not manufacture a floating account") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val storeA = newStore(sec.token, "Alpha")
                    val storeB = newStore(sec.token, "Bravo")
                    val number = "CLOSED-${System.nanoTime()}"

                    val created = createAccount(sec.token, provider, storeA, number, """"circuitId":"CIRC-1",""")
                    created.status shouldBe HttpStatusCode.Created
                    val accountId = created.bodyAsText().asJson().data().str("id")

                    val closureProof = uploadPdfProof(sec.token, "closure_proof")
                    client.post("/stores/$storeB/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"reason":"Branch shut down","proofOfClosureId":"$closureProof"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val transferProof = uploadPdfProof(sec.token, "transfer_proof")
                    val transfer = client.post("/accounts/$accountId/transfer") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"newStoreId":"$storeB","proofId":"$transferProof"}""")
                    }
                    transfer.status shouldBe HttpStatusCode.Conflict
                    transfer.bodyAsText().asJson().str("message") shouldContain "closed"
                }
            }
        }
    }

    Given("an account serving out its 30-day termination grace") {
        When("the same identity is re-provisioned at that store") {
            Then("409 naming the pending termination, not a bare 'already exists'") {
                testApplication {
                    application { testModule() }
                    val sec = signIn(UserRole.SECRETARY)
                    val provider = newProvider(signIn().token)
                    val store = newStore(sec.token, "Alpha")
                    val number = "GRACE-${System.nanoTime()}"

                    val created = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                    created.status shouldBe HttpStatusCode.Created
                    val accountId = created.bodyAsText().asJson().data().str("id")

                    val deactProof = uploadPdfProof(sec.token, "deactivation_proof")
                    client.post("/accounts/$accountId/deactivate") {
                        header(HttpHeaders.Authorization, "Bearer ${sec.token}")
                        contentType(ContentType.Application.Json)
                        setBody("""{"proofId":"$deactProof"}""")
                    }.status shouldBe HttpStatusCode.OK

                    val again = createAccount(sec.token, provider, store, number, """"circuitId":"CIRC-1",""")
                    again.status shouldBe HttpStatusCode.Conflict
                    val message = again.bodyAsText().asJson().str("message")
                    message shouldContain "pending termination"
                    message shouldContain "grace period"
                }
            }
        }
    }
})
