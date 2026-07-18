package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*

/**
 * Phase 5 surface: PUT update wiring (via provider) and the top-level transfers group.
 */
class CrudAndTransfersSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

    Given("a sysadmin token") {
        When("updating a provider via PUT and reading /transfers") {
            Then("the update is applied and /transfers is an enveloped cursor page") {
                testApplication {
                    application { testModule() }

                    val token = client.post("/auth/dev-login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"mike.pgmobiledev@gmail.com"}""")
                    }.bodyAsText().asJson()["data"]!!.jsonObject["token"]!!.jsonPrimitive.content

                    val created = client.post("/providers") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"Crud-${System.nanoTime()}","paymentScheduleDay":5}""")
                    }
                    created.status shouldBe HttpStatusCode.Created
                    val id = created.bodyAsText().asJson()["data"]!!.jsonObject["id"]!!.jsonPrimitive.content

                    val newName = "Renamed-${System.nanoTime()}"
                    val put = client.put("/providers/$id") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"name":"$newName","paymentScheduleDay":20}""")
                    }
                    put.status shouldBe HttpStatusCode.OK
                    val putData = put.bodyAsText().asJson()["data"]!!.jsonObject
                    putData["name"]!!.jsonPrimitive.content shouldBe newName
                    putData["paymentScheduleDay"]!!.jsonPrimitive.int shouldBe 20

                    val transfers = client.get("/transfers") { header(HttpHeaders.Authorization, "Bearer $token") }
                    transfers.status shouldBe HttpStatusCode.OK
                    val td = transfers.bodyAsText().asJson()
                    td["result"]!!.jsonPrimitive.content shouldBe "success"
                    (td["data"]!!.jsonObject["items"] is JsonArray) shouldBe true
                    td["data"]!!.jsonObject.containsKey("nextCursor") shouldBe true
                }
            }
        }
    }
})
