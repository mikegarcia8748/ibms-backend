package com.puregoldbe.ibms.domain.error

/**
 * Business-rule failures raised by the domain/use-case layer. The Ktor
 * StatusPages plugin maps each to an HTTP status + ApiError at the boundary,
 * so use cases stay free of any framework/HTTP types.
 */
sealed class DomainError(message: String, val code: String) : RuntimeException(message) {

    /** Request payload violated a rule (e.g. rate <= 0, bad billingPeriod). 400. */
    class Validation(message: String, code: String = "validation_error") : DomainError(message, code)

    /** Entity not found. 404. */
    class NotFound(message: String, code: String = "not_found") : DomainError(message, code)

    /** Caller lacks the required role/permission. 403. */
    class Forbidden(message: String, code: String = "forbidden") : DomainError(message, code)

    /** Auth token missing/invalid. 401. */
    class Unauthorized(message: String, code: String = "unauthorized") : DomainError(message, code)

    /** Rule conflict (e.g. duplicate branch_code, double-billing, last sysadmin). 409. */
    class Conflict(message: String, code: String = "conflict") : DomainError(message, code)
}
