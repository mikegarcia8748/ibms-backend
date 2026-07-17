package com.puregoldbe.ibms.domain.valueobject

/**
 * A billing period in "YYYY-MM" form. Mirrors the CHECK constraint on
 * topsheets.billing_period and topsheet_details.billing_period.
 */
@JvmInline
value class BillingPeriod(val value: String) {
    init {
        require(REGEX.matches(value)) { "billingPeriod must be YYYY-MM, got: $value" }
    }

    /** Compact form used inside invoice numbers: YYYYMM. */
    fun compact(): String = value.replace("-", "")

    override fun toString(): String = value

    companion object {
        val REGEX = Regex("""^\d{4}-\d{2}$""")
        fun isValid(value: String?): Boolean = value != null && REGEX.matches(value)
    }
}
