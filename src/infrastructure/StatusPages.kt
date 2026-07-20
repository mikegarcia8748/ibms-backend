package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ErrorEnvelope
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

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
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorEnvelope("error", "500", "internal server error", null),
            )
        }
    }
}
