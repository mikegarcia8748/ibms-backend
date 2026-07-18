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
 * OCR pipeline (simulated): trigger extraction -> batch + rows, list batches, read the
 * seeded templates, and create/update a template.
 */
class OcrSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

    Given("the OCR pipeline backed by the simulated extractor") {
        When("triggering extraction and reading batches/rows/templates") {
            Then("a batch with rows is produced, templates are seeded, and templates CRUD works") {
                testApplication {
                    application { testModule() }

                    val token = client.post("/auth/dev-login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"mike.pgmobiledev@gmail.com"}""")
                    }.bodyAsText().asJson()["data"]!!.jsonObject["token"]!!.jsonPrimitive.content
                    fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $token")

                    // trigger extraction
                    val extract = client.post("/ocr/extract") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"billingMonth":"2026-09","templateKey":"auto"}""")
                    }
                    extract.status shouldBe HttpStatusCode.Created
                    val batch = extract.bodyAsText().asJson()["data"]!!.jsonObject
                    batch["method"]!!.jsonPrimitive.content shouldBe "simulated"
                    batch["status"]!!.jsonPrimitive.content shouldBe "extracted"
                    val batchId = batch["id"]!!.jsonPrimitive.content

                    // extracted rows
                    val rows = client.get("/ocr/batches/$batchId/rows") { auth() }
                    rows.status shouldBe HttpStatusCode.OK
                    val rowsArr = rows.bodyAsText().asJson()["data"]!!.jsonArray
                    rowsArr.size shouldBe 3
                    rowsArr[0].jsonObject["billingPeriod"]!!.jsonPrimitive.content shouldBe "2026-09"

                    // batches list is an enveloped cursor page
                    val batches = client.get("/ocr/batches") { auth() }
                    batches.status shouldBe HttpStatusCode.OK
                    (batches.bodyAsText().asJson()["data"]!!.jsonObject["items"] is JsonArray) shouldBe true

                    // templates were seeded (V2)
                    val templates = client.get("/ocr/templates") { auth() }
                    templates.status shouldBe HttpStatusCode.OK
                    val tArr = templates.bodyAsText().asJson()["data"]!!.jsonArray
                    tArr.any { it.jsonObject["configKey"]!!.jsonPrimitive.content == "globe-corporate" } shouldBe true

                    // create + update a template
                    val key = "test-tpl-${System.nanoTime()}"
                    val created = client.post("/ocr/templates") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"configKey":"$key","formatName":"Test Layout"}""")
                    }
                    created.status shouldBe HttpStatusCode.Created
                    val tid = created.bodyAsText().asJson()["data"]!!.jsonObject["id"]!!.jsonPrimitive.content

                    val updated = client.put("/ocr/templates/$tid") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody("""{"configKey":"$key","formatName":"Updated Layout"}""")
                    }
                    updated.status shouldBe HttpStatusCode.OK
                    updated.bodyAsText().asJson()["data"]!!.jsonObject["formatName"]!!.jsonPrimitive.content shouldBe "Updated Layout"
                }
            }
        }
    }
})
