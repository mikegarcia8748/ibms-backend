package com.puregoldbe.ibms.domain.valueobject

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

    fun parse(wire: String?): BigDecimal =
        BigDecimal((wire ?: "0").trim().ifBlank { "0" }).setScale(SCALE, RoundingMode.HALF_UP)

    fun format(value: BigDecimal): String =
        value.setScale(SCALE, RoundingMode.HALF_UP).toPlainString()

    fun isPositive(wire: String?): Boolean = parse(wire) > BigDecimal.ZERO
}

/** Wire-string -> scaled BigDecimal. */
fun String?.toMoney(): BigDecimal = Money.parse(this)

/** Scaled BigDecimal -> wire string. */
fun BigDecimal.toMoneyString(): String = Money.format(this)
