package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ApiError
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*

/** Maps domain errors (and anything uncaught) to ApiError responses at the boundary. */
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
            call.respond(status, ApiError(cause.message ?: "error", cause.code))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal server error", "internal_error"))
        }
    }
}
