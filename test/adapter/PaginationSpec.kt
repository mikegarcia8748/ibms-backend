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
 * Verifies cursor pagination end-to-end: keyset (created_at, id) paging returns each
 * row exactly once across pages, respects `limit`, and terminates with nextCursor=null.
 */
class PaginationSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject

    Given("several providers created via the API") {
        When("walking every page with a small limit") {
            Then("each created provider appears exactly once and paging terminates") {
                testApplication {
                    application { testModule() }

                    val login = client.post("/auth/dev-login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"email":"mike.pgmobiledev@gmail.com"}""")
                    }
                    val token = login.bodyAsText().asJson()["data"]!!.jsonObject["token"]!!.jsonPrimitive.content

                    // create a known set of providers
                    val createdIds = (1..3).map { i ->
                        val name = "PageProvider-$i-${System.nanoTime()}"
                        val r = client.post("/providers") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                            contentType(ContentType.Application.Json)
                            setBody("""{"name":"$name","paymentScheduleDay":5}""")
                        }
                        r.status shouldBe HttpStatusCode.Created
                        r.bodyAsText().asJson()["data"]!!.jsonObject["id"]!!.jsonPrimitive.content
                    }

                    // page through ALL providers with limit=2, collecting ids
                    val seen = mutableListOf<String>()
                    var cursor: String? = null
                    var guard = 0
                    do {
                        val url = "/providers?limit=2" + (cursor?.let { "&cursor=$it" } ?: "")
                        val page = client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }
                        page.status shouldBe HttpStatusCode.OK
                        val data = page.bodyAsText().asJson()["data"]!!.jsonObject
                        val items = data["items"]!!.jsonArray
                        (items.size <= 2) shouldBe true
                        items.forEach { seen += it.jsonObject["id"]!!.jsonPrimitive.content }
                        cursor = data["nextCursor"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
                        if (++guard > 500) error("pagination did not terminate")
                    } while (cursor != null)

                    // keyset stability: no row repeated, and each created provider seen exactly once
                    seen.size shouldBe seen.toSet().size
                    createdIds.forEach { id -> seen.count { it == id } shouldBe 1 }
                }
            }
        }
    }
})
