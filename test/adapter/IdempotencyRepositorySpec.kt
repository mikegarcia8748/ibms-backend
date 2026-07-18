package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.repository.ExposedIdempotencyKeyRepository
import com.puregoldbe.ibms.support.PostgresTestDb
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration spec for the idempotency store against real Postgres (also proves the
 * V5 migration applied): reserve is once-only per (scope, key), and complete makes the
 * stored response replayable.
 */
class IdempotencyRepositorySpec : BehaviorSpec({

    val db = PostgresTestDb.database
    val repo = ExposedIdempotencyKeyRepository()

    Given("a fresh (scope, key)") {
        val scope = "topsheet.pay"
        val key = "key-${System.nanoTime()}"

        When("reserving twice then completing") {
            Then("reserve succeeds once, and the completed response is replayable") {
                transaction(db) {
                    repo.find(scope, key).shouldBeNull()

                    repo.reserve(scope, key, userId = null, requestHash = "h1") shouldBe true
                    repo.find(scope, key)!!.responseBody.shouldBeNull()      // reserved, not completed

                    repo.reserve(scope, key, userId = null, requestHash = "h1") shouldBe false // once-only

                    repo.complete(scope, key, responseStatus = 200, responseBody = """{"ok":true}""")
                    val rec = repo.find(scope, key)
                    rec.shouldNotBeNull()
                    rec.responseStatus shouldBe 200
                    rec.responseBody shouldBe """{"ok":true}"""
                    rec.requestHash shouldBe "h1"
                }
            }
        }
    }
})
