package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.infrastructure.config.AppConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Keeps .env.example honest: it is the canonical key list, and this fails the build
 * when it and AppConfig disagree.
 *
 * Both directions matter. An undocumented key is invisible to whoever deploys this; a
 * documented key that nothing reads is worse than absent, because setting it looks
 * like it did something — which is exactly how BOOTSTRAP_ADMIN_EMAIL survived in three
 * documents and two env files after the code stopped reading it.
 *
 * The key set is observed by recording what `fromEnv` actually looks up, so there is
 * no second table to maintain and no new drift surface.
 */
class ConfigKeysSpec : StringSpec({

    /** Keys consumed outside AppConfig, so they legitimately never reach it. */
    val readElsewhere = setOf(
        // resources/application.yaml interpolates this for Ktor's engine.
        "APP_PORT",
    )

    /**
     * Every variable `fromEnv` looks up. Two passes because the SMTP block is only
     * read when delivery is smtp; the config itself is discarded, and so is any
     * validation failure — only the key names matter here.
     */
    val keysRead: Set<String> = buildSet {
        listOf(
            mapOf("APP_ENV" to "dev"),
            mapOf("APP_ENV" to "dev", "EMAIL_DELIVERY" to "smtp", "SMTP_HOST" to "relay.internal"),
        ).forEach { env ->
            runCatching { AppConfig.fromEnv { key -> add(key); env[key] } }
        }
    }

    val documented: Set<String> = File(".env.example").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringBefore("=").trim() }
        .toSet()

    "the fixtures are where this spec expects them" {
        File(".env.example").isFile shouldBe true
        documented.isEmpty() shouldBe false
        keysRead.isEmpty() shouldBe false
    }

    "every key AppConfig reads is documented in .env.example" {
        (keysRead - documented).shouldBeEmpty()
    }

    "every key documented in .env.example is actually read" {
        (documented - keysRead - readElsewhere).shouldBeEmpty()
    }
})
