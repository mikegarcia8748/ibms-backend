package com.puregoldbe.ibms.infrastructure

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Guards the Flyway migration filenames.
 *
 * Two branches independently claimed V19 and both merged, which made Flyway refuse to
 * migrate at all — taking down app startup and every integration spec at once. The
 * collision is invisible in a diff (each side only adds a file) but trivial to catch
 * here, at PR time rather than at boot.
 */
class MigrationVersionsSpec : StringSpec({

    val dir = File("resources/db/migration")
    val versions = dir.listFiles { f: File -> f.name.endsWith(".sql") }
        .orEmpty()
        .map { it.name }
        .map { name -> name.substringBefore("__").removePrefix("V").toIntOrNull() to name }

    "the migration directory is where this spec expects it" {
        dir.isDirectory shouldBe true
        versions.isNotEmpty() shouldBe true
    }

    "every migration filename carries a parseable version" {
        versions.filter { it.first == null }.map { it.second }.shouldBeEmpty()
    }

    "no two migrations share a version" {
        val duplicates = versions.mapNotNull { it.first }
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicates.shouldBeEmpty()
    }

    "versions run 1..N with no gaps" {
        val sorted = versions.mapNotNull { it.first }.sorted()
        sorted shouldBe (1..sorted.size).toList()
    }
})
