package com.puregoldbe.ibms.domain.valueobject

import com.puregoldbe.ibms.domain.error.DomainError
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

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

    /**
     * Display-only: `"555335.00"` -> `"₱555,335.00"`. For human-facing surfaces such as
     * notification emails — **never** for the wire, where [format] is the contract and a
     * grouped, symbol-prefixed string would break every client parsing it back.
     *
     * Falls back to the raw input rather than throwing, unlike [parse]: the only caller
     * so far renders inside the triggering use case's transaction, where an exception
     * would roll back a real business write over a formatting problem.
     */
    fun display(wire: String?): String {
        val value = parseOrNull(wire) ?: return wire.orEmpty()
        val symbols = DecimalFormatSymbols(Locale.US)
        return "₱" + DecimalFormat("#,##0.00", symbols).format(value)
    }

    fun isPositive(wire: String?): Boolean = parse(wire) > BigDecimal.ZERO
}

/** Wire-string -> scaled BigDecimal. */
fun String?.toMoney(): BigDecimal = Money.parse(this)

/** Wire-string -> scaled BigDecimal, or null when unparseable. */
fun String?.toMoneyOrNull(): BigDecimal? = Money.parseOrNull(this)

/** Scaled BigDecimal -> wire string. */
fun BigDecimal.toMoneyString(): String = Money.format(this)
