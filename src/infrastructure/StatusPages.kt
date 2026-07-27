package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ErrorEnvelope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException

/** The first non-blank SQLSTATE found while walking this throwable's cause chain, or null. */
internal fun Throwable.sqlStateOrNull(): String? {
    var t: Throwable? = this
    while (t != null) {
        val state = (t as? SQLException)?.sqlState
        if (!state.isNullOrBlank()) return state
        t = t.cause
    }
    return null
}

/** Maps domain errors (and anything uncaught) to the unified error envelope at the boundary. */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<DomainError> { call, cause ->
            val status = when (cause) {
                is DomainError.Validation -> HttpStatusCode.BadRequest
                is DomainError.Unauthorized -> HttpStatusCode.Unauthorized
                is DomainError.Forbidden -> HttpStatusCode.Forbidden
                is DomainError.NotFound -> HttpStatusCode.NotFound
                is DomainError.Conflict -> HttpStatusCode.Conflict
            }
            // `code` stays server-side (logged/available on DomainError) but is not on the wire.
            call.respond(status, ErrorEnvelope("error", status.value.toString(), cause.message ?: "error", null))
        }
        // A Postgres unique-constraint violation (SQLSTATE 23505) — e.g. a compile/confirm race on
        // uq_account_per_period, or uq_draft_per_provider_period — is a conflict, not a 500.
        exception<ExposedSQLException> { call, cause ->
            if (cause.sqlStateOrNull() == "23505") {
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorEnvelope("error", "409", "resource already exists", null),
                )
            } else {
                call.application.log.error("Unhandled SQL error", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorEnvelope("error", "500", "internal server error", null),
                )
            }
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope("error", "500", "internal server error", null),
            )
        }
    }
}
