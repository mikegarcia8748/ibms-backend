package com.puregoldbe.ibms.support

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Proof-upload helpers for integration specs. Since the app now requires proof
 * attachments (subscription / transfer / deactivation) to be real, fully-uploaded
 * PDFs, specs must presign as `application/pdf` and PUT actual PDF bytes rather than
 * leaving a presigned-but-empty row.
 */

/** A minimal but valid PDF payload (starts with the `%PDF` magic). */
val PDF_BYTES: ByteArray = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".toByteArray()

/** Bytes that are deliberately NOT a PDF, for negative tests. */
val NOT_PDF_BYTES: ByteArray = "this is definitely not a pdf".toByteArray()

/**
 * Presign a proof upload and PUT real PDF bytes over the public blob route, leaving a
 * fully-uploaded PDF attachment the account use cases will accept.
 *
 * @return the attachment id.
 */
suspend fun ApplicationTestBuilder.uploadPdfProof(
    token: String,
    purpose: String = "subscription_proof",
    fileName: String = "proof.pdf",
    bytes: ByteArray = PDF_BYTES,
): String {
    val presign = client.post("/attachments/presign/upload") {
        header(HttpHeaders.Authorization, "Bearer $token")
        contentType(ContentType.Application.Json)
        setBody("""{"fileName":"$fileName","contentType":"application/pdf","purpose":"$purpose"}""")
    }
    check(presign.status == HttpStatusCode.OK) { "presign failed: ${presign.status} ${presign.bodyAsText()}" }
    val data = Json.parseToJsonElement(presign.bodyAsText()).jsonObject["data"]!!.jsonObject
    val attachmentId = data["attachmentId"]!!.jsonPrimitive.content
    val url = data["url"]!!.jsonPrimitive.content.removePrefix("http://localhost:8080")
    val put = client.put(url) { setBody(bytes) }
    check(put.status == HttpStatusCode.OK) { "blob PUT failed: ${put.status} ${put.bodyAsText()}" }
    return attachmentId
}
