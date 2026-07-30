package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.db.EmailLog
import com.puregoldbe.ibms.adapter.db.Users
import com.puregoldbe.ibms.adapter.gateway.ExposedTransactionRunner
import com.puregoldbe.ibms.adapter.gateway.SimulatedEmailGateway
import com.puregoldbe.ibms.adapter.repository.ExposedEmailLogRepository
import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.application.usecase.DispatchQueuedEmailsUseCase
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.PostgresTestDb
import com.puregoldbe.ibms.support.signIn
import com.puregoldbe.ibms.support.testModule
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * End-to-end notification wiring against the real composition root + Postgres:
 * a sysad subscribes a user (with an email) to `store.created`; creating a store
 * over HTTP enqueues an `email_log` row addressed to that user; the dispatcher
 * (with the simulated gateway) drains it to `simulated`. Also covers the
 * sysad-only guard on the subscription endpoints. Uses a unique recipient email so
 * it never collides with the shared Testcontainers database.
 */
class NotificationFlowSpec : BehaviorSpec({

    fun String.asJson(): JsonObject = Json.parseToJsonElement(this).jsonObject
    val users = ExposedUserRepository()

    /** Seed an ACTIVE user carrying [email] (signIn() leaves email null), return its id. */
    fun seedUserWithEmail(role: UserRole, email: String): String = transaction(PostgresTestDb.database) {
        val now = Clock.System.now()
        val created = users.create(
            input = ProvisionUserRequest(username = "notif-${System.nanoTime()}", name = "Notif $role", role = role),
            passwordHash = "x",
            tempPasswordExpiresAt = now + 1.days,
            at = now,
        )
        Users.update({ Users.id eq UUID.fromString(created.id) }) {
            it[Users.email] = email
            it[Users.status] = UserStatus.ACTIVE
        }
        created.id
    }

    Given("a user subscribed to store.created and a store is then created") {
        Then("the outbox holds a queued email to that user, and the dispatcher delivers it") {
            testApplication {
                application { testModule() }
                val admin = signIn(UserRole.SYSADMIN).token
                val recipientEmail = "recip-${System.nanoTime()}@test.local"
                val recipientId = seedUserWithEmail(UserRole.FINANCE, recipientEmail)

                // sysad subscribes the recipient to store.created via the profile API
                val put = client.put("/users/$recipientId/notification-subscriptions") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"events":["store.created"]}""")
                }
                put.status shouldBe HttpStatusCode.OK
                val putData = put.bodyAsText().asJson()["data"]!!.jsonObject
                putData["subscribed"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContain "store.created"
                putData["available"]!!.jsonArray.size shouldBe 8

                // GET reflects the same set
                val get = client.get("/users/$recipientId/notification-subscriptions") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                }
                get.bodyAsText().asJson()["data"]!!.jsonObject["subscribed"]!!.jsonArray
                    .map { it.jsonPrimitive.content } shouldContain "store.created"

                // create a store (installation proof only needs to exist, not be uploaded)
                val attachmentId = client.post("/attachments/presign/upload") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fileName":"proof.pdf","contentType":"application/pdf","purpose":"installation_proof"}""")
                }.bodyAsText().asJson()["data"]!!.jsonObject["attachmentId"]!!.jsonPrimitive.content
                val branch = "NTF-${System.nanoTime()}"
                val store = client.post("/stores") {
                    header(HttpHeaders.Authorization, "Bearer $admin")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"storeType":"puregold","branchCode":"$branch","name":"Notif Store","proofOfInstallationId":"$attachmentId"}""",
                    )
                }
                store.status shouldBe HttpStatusCode.Created

                // an email_log row addressed to the recipient was enqueued
                fun myRow(): Triple<String, List<String>, String>? = transaction(PostgresTestDb.database) {
                    EmailLog.selectAll().where { EmailLog.type eq "store.created" }
                        .map { Triple(it[EmailLog.id].value.toString(), it[EmailLog.toEmails], it[EmailLog.status]) }
                        .firstOrNull { recipientEmail in it.second }
                }
                val enqueued = myRow()
                (enqueued != null) shouldBe true
                enqueued!!.second shouldContain recipientEmail

                // drain the outbox with the simulated gateway; the row reaches a terminal state
                DispatchQueuedEmailsUseCase(
                    ExposedEmailLogRepository(),
                    SimulatedEmailGateway(),
                    FakeClock(),
                    ExposedTransactionRunner(PostgresTestDb.database),
                    batchSize = 5000,
                )()
                myRow()!!.third shouldBe "simulated"
            }
        }
    }

    Given("a non-sysadmin caller") {
        Then("updating notification subscriptions is forbidden") {
            testApplication {
                application { testModule() }
                val secretary = signIn(UserRole.SECRETARY).token
                val someUser = seedUserWithEmail(UserRole.MANAGER, "mgr-${System.nanoTime()}@test.local")
                val res = client.put("/users/$someUser/notification-subscriptions") {
                    header(HttpHeaders.Authorization, "Bearer $secretary")
                    contentType(ContentType.Application.Json)
                    setBody("""{"events":["store.created"]}""")
                }
                res.status shouldBe HttpStatusCode.Forbidden
            }
        }
    }
})
