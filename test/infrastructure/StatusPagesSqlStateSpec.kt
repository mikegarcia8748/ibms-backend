package com.puregoldbe.ibms.infrastructure

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.SQLException

/**
 * Unit coverage for the SQLSTATE extraction that lets StatusPages map a Postgres
 * unique-violation (23505) to a 409 instead of a raw 500.
 */
class StatusPagesSqlStateSpec : StringSpec({

    "a direct SQLException exposes its SQLSTATE" {
        SQLException("duplicate key", "23505").sqlStateOrNull() shouldBe "23505"
    }

    "a nested SQLException cause is found by walking the chain" {
        RuntimeException("wrapper", SQLException("duplicate key", "23505")).sqlStateOrNull() shouldBe "23505"
    }

    "a throwable with no SQLException in the chain has no SQLSTATE" {
        RuntimeException("plain failure").sqlStateOrNull() shouldBe null
    }

    "a blank SQLSTATE is skipped in favor of a populated one deeper in the chain" {
        val outer = SQLException("outer", "")
        outer.initCause(SQLException("inner", "23505"))
        outer.sqlStateOrNull() shouldBe "23505"
    }
})
