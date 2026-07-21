package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ApproveAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.CancelAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.GetAccountChangeRequestWithDiffUseCase
import com.puregoldbe.ibms.application.usecase.ListAccountChangeRequestsUseCase
import com.puregoldbe.ibms.application.usecase.RejectAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.SubmitAccountChangeRequestUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.SubmitAccountChangeRequestInput
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.accountChangeRequestRoutes(
    submit: SubmitAccountChangeRequestUseCase,
    approve: ApproveAccountChangeRequestUseCase,
    reject: RejectAccountChangeRequestUseCase,
    cancel: CancelAccountChangeRequestUseCase,
    getWithDiff: GetAccountChangeRequestWithDiffUseCase,
    list: ListAccountChangeRequestsUseCase,
) {
    route("/accounts/{id}/change-requests") {
        // POST - Submit change request (secretary only)
        post {
            val caller = call.authorize(UserRole.SECRETARY)
            val input = call.receive<SubmitAccountChangeRequestInput>()
            call.created(submit(call.pathId(), input, caller.userId))
        }

        // GET - List change requests for account
        get {
            call.authorize()
            val p = call.pageParams()
            val status = parseAccountChangeRequestStatus(call.request.queryParameters["status"])
            call.ok(list(accountId = call.pathId(), status = status, cursor = p.cursor, limit = p.limit))
        }

        // GET - Get single request with diff
        get("/{requestId}") {
            call.authorize()
            val requestId = call.parameters["requestId"] ?: throw DomainError.Validation("requestId required")
            call.ok(getWithDiff(requestId))
        }

        // POST - Approve (manager only)
        post("/{requestId}/approve") {
            val caller = call.authorize(UserRole.MANAGER)
            val requestId = call.parameters["requestId"] ?: throw DomainError.Validation("requestId required")
            call.ok(approve(requestId, caller.userId))
        }

        // POST - Reject (manager only)
        post("/{requestId}/reject") {
            val caller = call.authorize(UserRole.MANAGER)
            val requestId = call.parameters["requestId"] ?: throw DomainError.Validation("requestId required")
            val body = call.receive<RejectChangeRequestBody>()
            call.ok(reject(requestId, body.reason, caller.userId))
        }

        // POST - Cancel (secretary only, own request)
        post("/{requestId}/cancel") {
            val caller = call.authorize(UserRole.SECRETARY)
            val requestId = call.parameters["requestId"] ?: throw DomainError.Validation("requestId required")
            call.ok(cancel(requestId, caller.userId))
        }
    }
}
