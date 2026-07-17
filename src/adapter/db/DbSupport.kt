package com.puregoldbe.ibms.adapter.db

import java.util.UUID

/**
 * Adapter-internal conversions between JDBC/java-time (Exposed column types) and
 * the kotlinx types used by the domain/wire models. Done by hand rather than via
 * kotlinx-datetime's JVM interop extensions, which are `internal` in 0.6.2.
 */

internal fun String.toUuid(): UUID = UUID.fromString(this)
internal fun String.toUuidOrNull(): UUID? = runCatching { UUID.fromString(this) }.getOrNull()

internal fun java.time.Instant.kx(): kotlinx.datetime.Instant =
    kotlinx.datetime.Instant.fromEpochSeconds(epochSecond, nano.toLong())

internal fun kotlinx.datetime.Instant.jt(): java.time.Instant =
    java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

internal fun java.time.LocalDate.kx(): kotlinx.datetime.LocalDate =
    kotlinx.datetime.LocalDate(year, monthValue, dayOfMonth)

internal fun kotlinx.datetime.LocalDate.jt(): java.time.LocalDate =
    java.time.LocalDate.of(year, monthNumber, dayOfMonth)
