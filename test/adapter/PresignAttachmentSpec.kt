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
 * End-to-end local presign flow: presign an upload, PUT the bytes over the public
 * token-gated blob route, presign a download, and read the same bytes back. Also
 * proves a tampered token is rejected.
 */
class PresignAttachmentSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    fun String.toRelative(): String = removePrefix("http://localhost:8080")

    Given("an authenticated user and the local presign flow") {
        When("presigning an upload, PUTting bytes, then reading them back") {
            Then("the round-trip returns the same bytes and a tampered token is rejected") {
                testApplication {
                    application { testModule() }

                    val token = client.post("/auth/dev-login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"mike.pgmobiledev@gmail.com"}""")
                    }.bodyAsText().asJson()["data"]!!.jsonObject["token"]!!.jsonPrimitive.content

                    // 1. presign upload
                    val presign = client.post("/attachments/presign/upload") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fileName":"proof.txt","contentType":"text/plain","purpose":"installation_proof"}""")
                    }
                    presign.status shouldBe HttpStatusCode.OK
                    val pd = presign.bodyAsText().asJson()["data"]!!.jsonObject
                    val attachmentId = pd["attachmentId"]!!.jsonPrimitive.content
                    val uploadUrl = pd["url"]!!.jsonPrimitive.content.toRelative()

                    // 2. PUT the bytes to the public, token-gated blob route
                    val payload = "hello-proof"
                    val put = client.put(uploadUrl) { setBody(payload.toByteArray()) }
                    put.status shouldBe HttpStatusCode.OK
                    put.bodyAsText().asJson()["result"]!!.jsonPrimitive.content shouldBe "success"

                    // 3. presign download
                    val dl = client.get("/attachments/$attachmentId/presign/download") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                    }
                    dl.status shouldBe HttpStatusCode.OK
                    val downloadUrl = dl.bodyAsText().asJson()["data"]!!.jsonObject["url"]!!.jsonPrimitive.content.toRelative()

                    // 4. GET the bytes back
                    val blob = client.get(downloadUrl)
                    blob.status shouldBe HttpStatusCode.OK
                    blob.bodyAsText() shouldBe payload

                    // 5. a tampered token is rejected
                    val bad = client.get("/attachments/$attachmentId/blob?token=99999999999:deadbeef")
                    bad.status shouldBe HttpStatusCode.Unauthorized
                }
            }
        }
    }
})
