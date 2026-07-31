package com.puregoldbe.ibms.domain.valueobject

import com.puregoldbe.ibms.domain.error.DomainError
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Money handling for the billing domain.
 *
 * On the wire, money is a 2dp decimal String (see `Money` typealias in the model).
 * In the DB it is numeric(14,2) -> BigDecimal. These helpers are the single place
 * that converts between the two, always at scale 2, HALF_UP. Never use Double.
 */
object Money {
    const val SCALE = 2

    /**
     * Wire string -> scaled BigDecimal.
     *
     * Because money is a `String` on the wire, an unparseable amount survives
     * deserialization untouched and only fails here. Raise a [DomainError.Validation]
     * (-> 400) rather than letting BigDecimal's `NumberFormatException` escape to
     * StatusPages, which answers 500 to what is plainly a bad request.
     */
    fun parse(wire: String?): BigDecimal =
        parseOrNull(wire) ?: throw DomainError.Validation("amount must be a valid decimal number")

    /** As [parse], but null instead of throwing — for callers with a field-specific message. */
    fun parseOrNull(wire: String?): BigDecimal? =
        try {
            BigDecimal((wire ?: "0").trim().ifBlank { "0" }).setScale(SCALE, RoundingMode.HALF_UP)
        } catch (_: NumberFormatException) {
            null
        }

    fun format(value: BigDecimal): String =
        value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString()

    fun isPositive(wire: String?): Boolean = parse(wire) > BigDecimal.ZERO
}

/** Wire-string -> scaled BigDecimal. */
fun String?.toMoney(): BigDecimal = Money.parse(this)

/** Wire-string -> scaled BigDecimal, or null when unparseable. */
fun String?.toMoneyOrNull(): BigDecimal? = Money.parseOrNull(this)

/** Scaled BigDecimal -> wire string. */
fun BigDecimal.toMoneyString(): String = Money.format(this)
