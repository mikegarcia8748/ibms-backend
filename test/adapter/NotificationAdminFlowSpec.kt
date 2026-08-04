package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.PostgresTestDb
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.days

/**
 * End-to-end cover for the sysadmin notification administration surface
 * (`apicontracts/NOTIFICATION_SUBSCRIPTION_ADMIN_API_CONTRACT.md`) against the real
 * composition root + Postgres.
 *
 * Specs share one non-truncated database, so nothing here asserts a global count or a
 * page size: every user is seeded with a unique email, and the matrix is *paged* to
 * locate a specific row rather than assumed to be on the first page. Role-targeted
 * writes deliberately use PENDING, the one role no other spec depends on for delivery.
 */
class NotificationAdminFlowSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    suspend fun HttpResponse.data(): JsonObject = bodyAsText().asJson()["data"]!!.jsonObject
    val users = ExposedUserRepository()

    /** Seed an ACTIVE user straight through the repository; [email] may be null. */
    fun seedUser(role: UserRole, email: String?): String = transaction(PostgresTestDb.database) {
        val now = Clock.System.now()
        users.create(
            input = ProvisionUserRequest(
                username = "nadm${System.nanoTime().toString().takeLast(11)}",
                name = "NotifAdmin ${role.name.lowercase()}",
                email = email,
                role = role,
            ),
            passwordHash = "x",
            tempPasswordExpiresAt = now + 1.days,
            at = now,
        ).id
    }

    /**
     * Walk the matrix until [userId] turns up. The shared database means the row can be
     * on any page, so the first page is never sufficient.
     */
    suspend fun findRow(client: HttpClient, token: String, query: String, userId: String): JsonObject? {
        var cursor: String? = null
        repeat(100) {
            val url = "/admin/notifications/subscriptions?limit=100&$query" +
                (cursor?.let { "&cursor=$it" } ?: "")
            val body = client.get(url) { header(HttpHeaders.Authorization, "Bearer $token") }
                .bodyAsText().asJson()["data"]!!.jsonObject
            body["items"]!!.jsonArray
                .firstOrNull { it.jsonObject["userId"]!!.jsonPrimitive.content == userId }
                ?.let { return it.jsonObject }
            cursor = body["nextCursor"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
                ?: return null
        }
        return null
    }

    suspend fun subscribe(client: HttpClient, token: String, userId: String, vararg events: String) {
        val res = client.put("/users/$userId/notification-subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"events":[${events.joinToString(",") { "\"$it\"" }}]}""")
        }
        res.status shouldBe HttpStatusCode.OK
    }

    suspend fun subscriptionsOf(client: HttpClient, token: String, userId: String): List<String> =
        client.get("/users/$userId/notification-subscriptions") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }.bodyAsText().asJson()["data"]!!.jsonObject["subscribed"]!!.jsonArray
            .map { it.jsonPrimitive.content }

    Given("the event catalogue") {
        Then("it lists every subscribable event with the copy the frontend renders") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token

                val res = client.get("/admin/notifications/events") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }
                res.status shouldBe HttpStatusCode.OK
                val events = res.data()["events"]!!.jsonArray
                events.size shouldBe 8

                val keys = events.map { it.jsonObject["key"]!!.jsonPrimitive.content }
                keys shouldContainAll listOf(
                    "store.created", "account.created", "account.updated", "account.transferred",
                    "account.deactivation_requested", "account.terminated",
                    "topsheet.compiled", "topsheet.released",
                )
                // Every entry must carry the label + description the grid needs, and a count.
                events.forEach { entry ->
                    val o = entry.jsonObject
                    o["label"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                    o["description"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                    o["deliverableSubscribers"]!!.jsonPrimitive.int shouldBeGreaterThanOrEqual 0
                }
            }
        }
    }

    Given("a subscribed user who has an email address") {
        Then("the matrix reports them deliverable and the catalogue counts them") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val email = "deliverable-${System.nanoTime()}@test.local"
                val userId = seedUser(UserRole.FINANCE, email)

                subscribe(client, admin, userId, "account.terminated")

                val row = findRow(client, admin, "event=account.terminated", userId)
                (row != null) shouldBe true
                row!!["email"]!!.jsonPrimitive.content shouldBe email
                row["role"]!!.jsonPrimitive.content shouldBe "finance"
                row["status"]!!.jsonPrimitive.content shouldBe "active"
                row["deliverable"]!!.jsonPrimitive.boolean shouldBe true
                row["notDeliverableReason"]!! shouldBe JsonNull
                row["subscribed"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContain "account.terminated"

                // The catalogue count is org-wide, so assert it now includes this user
                // rather than asserting an exact total on a shared database.
                val count = client.get("/admin/notifications/events") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }.data()["events"]!!.jsonArray
                    .first { it.jsonObject["key"]!!.jsonPrimitive.content == "account.terminated" }
                    .jsonObject["deliverableSubscribers"]!!.jsonPrimitive.int
                count shouldBeGreaterThanOrEqual 1
            }
        }
    }

    Given("a subscribed user with no email address") {
        Then("the matrix flags them undeliverable and the worklist filter finds them") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val userId = seedUser(UserRole.MANAGER, null)

                subscribe(client, admin, userId, "topsheet.compiled")

                val row = findRow(client, admin, "deliverable=false", userId)
                (row != null) shouldBe true
                row!!["email"]!! shouldBe JsonNull
                row["deliverable"]!!.jsonPrimitive.boolean shouldBe false
                row["notDeliverableReason"]!!.jsonPrimitive.content shouldBe "no_email"

                // ...and they are absent from the deliverable side of the same filter.
                findRow(client, admin, "deliverable=true", userId) shouldBe null
            }
        }
    }

    Given("two users targeted by a bulk write") {
        Then("add is idempotent, and remove and replace narrow the set") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val u1 = seedUser(UserRole.FINANCE, "bulk1-${System.nanoTime()}@test.local")
                val u2 = seedUser(UserRole.FINANCE, "bulk2-${System.nanoTime()}@test.local")

                suspend fun bulk(body: String): JsonObject {
                    val res = client.post("/admin/notifications/subscriptions/bulk") {
                        header(HttpHeaders.Authorization, "Bearer $admin")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    res.status shouldBe HttpStatusCode.OK
                    return res.data()
                }

                val added = bulk(
                    """{"mode":"add","events":["account.terminated","account.transferred"],
                       |"userIds":["$u1","$u2"]}""".trimMargin(),
                )
                added["usersMatched"]!!.jsonPrimitive.int shouldBe 2
                added["usersChanged"]!!.jsonPrimitive.int shouldBe 2
                added["undeliverableTargets"]!!.jsonPrimitive.int shouldBe 0
                added["events"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf("account.terminated", "account.transferred")

                // Re-running the same add changes nobody — a successful no-op, not an error.
                val again = bulk(
                    """{"mode":"add","events":["account.terminated","account.transferred"],
                       |"userIds":["$u1","$u2"]}""".trimMargin(),
                )
                again["usersMatched"]!!.jsonPrimitive.int shouldBe 2
                again["usersChanged"]!!.jsonPrimitive.int shouldBe 0

                val removed = bulk("""{"mode":"remove","events":["account.transferred"],"userIds":["$u1"]}""")
                removed["usersChanged"]!!.jsonPrimitive.int shouldBe 1
                subscriptionsOf(client, admin, u1) shouldBe listOf("account.terminated")
                // u2 is untouched by a write that named only u1.
                subscriptionsOf(client, admin, u2) shouldContain "account.transferred"

                val replaced = bulk("""{"mode":"replace","events":[],"userIds":["$u1","$u2"]}""")
                replaced["usersChanged"]!!.jsonPrimitive.int shouldBe 2
                subscriptionsOf(client, admin, u1) shouldBe emptyList<String>()
                subscriptionsOf(client, admin, u2) shouldBe emptyList<String>()
            }
        }
    }

    Given("a bulk write targeting a whole role") {
        Then("it reaches members of that role without naming them") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val pending = seedUser(UserRole.PENDING, "role-target-${System.nanoTime()}@test.local")

                val res = client.post("/admin/notifications/subscriptions/bulk") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"add","events":["account.updated"],"roles":["pending"]}""")
                }
                res.status shouldBe HttpStatusCode.OK
                res.data()["usersMatched"]!!.jsonPrimitive.int shouldBeGreaterThanOrEqual 1
                subscriptionsOf(client, admin, pending) shouldContain "account.updated"

                // Clean up so a later spec provisioning a PENDING user is unaffected.
                client.post("/admin/notifications/subscriptions/bulk") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"remove","events":["account.updated"],"roles":["pending"]}""")
                }
                subscriptionsOf(client, admin, pending) shouldNotContain "account.updated"
            }
        }
    }

    Given("per-role defaults") {
        Then("a newly provisioned user inherits them and is immediately deliverable") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token

                suspend fun putDefaults(body: String): JsonObject {
                    val res = client.put("/admin/notifications/defaults") {
                        header(HttpHeaders.Authorization, "Bearer $admin")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                    res.status shouldBe HttpStatusCode.OK
                    return res.data()
                }

                val set = putDefaults(
                    """{"defaults":[{"role":"pending","events":["account.terminated"]}]}""",
                )
                // Every role comes back, so the client never has to handle a missing key.
                val roles = set["defaults"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
                roles shouldBe listOf("sysadmin", "secretary", "finance", "manager", "pending")
                set["defaults"]!!.jsonArray
                    .first { it.jsonObject["role"]!!.jsonPrimitive.content == "pending" }
                    .jsonObject["events"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf("account.terminated")

                // Provision through the real endpoint: the seed happens in that transaction.
                val email = "seeded-${System.nanoTime()}@test.local"
                val username = "seed${System.nanoTime().toString().takeLast(11)}"
                val created = client.post("/users") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"$username","name":"Seeded User","email":"$email","role":"pending"}""")
                }
                created.status shouldBe HttpStatusCode.Created
                val newUser = created.data()["user"]!!.jsonObject
                val newUserId = newUser["id"]!!.jsonPrimitive.content
                newUser["email"]!!.jsonPrimitive.content shouldBe email

                subscriptionsOf(client, admin, newUserId) shouldBe listOf("account.terminated")
                val row = findRow(client, admin, "event=account.terminated", newUserId)
                (row != null) shouldBe true
                row!!["deliverable"]!!.jsonPrimitive.boolean shouldBe true

                // Restore the defaults so later specs provision with a clean template.
                putDefaults("""{"defaults":[{"role":"pending","events":[]}]}""")
                // Existing users keep what they were seeded with — defaults are a template.
                subscriptionsOf(client, admin, newUserId) shouldBe listOf("account.terminated")
            }
        }
    }

    Given("malformed admin requests") {
        Then("each is rejected with a 400 rather than a 500") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val user = seedUser(UserRole.FINANCE, "malformed-${System.nanoTime()}@test.local")

                suspend fun bulkStatus(body: String): HttpStatusCode =
                    client.post("/admin/notifications/subscriptions/bulk") {
                        header(HttpHeaders.Authorization, "Bearer $admin")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }.status

                bulkStatus("""{"mode":"add","events":["nope.invented"],"userIds":["$user"]}""") shouldBe
                    HttpStatusCode.BadRequest
                bulkStatus("""{"mode":"add","events":["store.created"],"roles":["payables"]}""") shouldBe
                    HttpStatusCode.BadRequest
                bulkStatus("""{"mode":"sideways","events":["store.created"],"userIds":["$user"]}""") shouldBe
                    HttpStatusCode.BadRequest
                // No targets at all.
                bulkStatus("""{"mode":"add","events":["store.created"]}""") shouldBe HttpStatusCode.BadRequest
                // add/remove need events; only replace may be empty.
                bulkStatus("""{"mode":"add","events":[],"userIds":["$user"]}""") shouldBe HttpStatusCode.BadRequest

                // An unknown user aborts the whole write.
                val missing = "00000000-0000-0000-0000-000000000000"
                bulkStatus("""{"mode":"add","events":["store.created"],"userIds":["$user","$missing"]}""") shouldBe
                    HttpStatusCode.NotFound
                subscriptionsOf(client, admin, user) shouldBe emptyList<String>()

                // Duplicate role in a defaults write is ambiguous, not merely redundant.
                client.put("/admin/notifications/defaults") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"defaults":[{"role":"manager","events":[]},{"role":"manager","events":[]}]}""",
                    )
                }.status shouldBe HttpStatusCode.BadRequest

                // An unrecognised event filter errors, unlike the lenient role/status filters.
                client.get("/admin/notifications/subscriptions?event=nope.invented") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }.status shouldBe HttpStatusCode.BadRequest
                client.get("/admin/notifications/subscriptions?role=payables") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }.status shouldBe HttpStatusCode.OK
            }
        }
    }

    Given("a subscribed user who has no delivery address") {
        Then("setting one normalises it and flips them to deliverable") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val userId = seedUser(UserRole.MANAGER, null)
                subscribe(client, admin, userId, "topsheet.released")

                findRow(client, admin, "deliverable=false", userId)!!["notDeliverableReason"]!!
                    .jsonPrimitive.content shouldBe "no_email"

                val email = "Set.Later-${System.nanoTime()}@Test.Local"
                val patched = client.patch("/users/$userId/email") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"  $email  "}""")
                }
                patched.status shouldBe HttpStatusCode.OK
                patched.data()["email"]!!.jsonPrimitive.content shouldBe email.lowercase()

                val row = findRow(client, admin, "deliverable=true", userId)
                (row != null) shouldBe true
                row!!["deliverable"]!!.jsonPrimitive.boolean shouldBe true
                row["notDeliverableReason"]!! shouldBe JsonNull

                // Clearing it puts them back out of reach without touching subscriptions.
                client.patch("/users/$userId/email") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":null}""")
                }.status shouldBe HttpStatusCode.OK
                findRow(client, admin, "deliverable=false", userId)!!["email"]!! shouldBe JsonNull
                subscriptionsOf(client, admin, userId) shouldBe listOf("topsheet.released")

                client.patch("/users/$userId/email") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"email":"not-an-address"}""")
                }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    Given("a non-sysadmin caller") {
        Then("every notification administration endpoint is forbidden") {
            testApplication {
                application { testModule() }
                val secretary = signIn(UserRole.SECRETARY).token
                fun HttpRequestBuilder.auth() = header(HttpHeaders.Authorization, "Bearer $secretary")

                client.get("/admin/notifications/events") { auth() }.status shouldBe HttpStatusCode.Forbidden
                client.get("/admin/notifications/subscriptions") { auth() }.status shouldBe HttpStatusCode.Forbidden
                client.get("/admin/notifications/defaults") { auth() }.status shouldBe HttpStatusCode.Forbidden
                client.post("/admin/notifications/subscriptions/bulk") {
                    auth()
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"add","events":["store.created"],"roles":["finance"]}""")
                }.status shouldBe HttpStatusCode.Forbidden
                client.put("/admin/notifications/defaults") {
                    auth()
                    contentType(ContentType.Application.Json)
                    setBody("""{"defaults":[]}""")
                }.status shouldBe HttpStatusCode.Forbidden
            }
        }
    }
})
