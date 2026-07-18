package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ExpireGracePeriodAccountsUseCase
import com.puregoldbe.ibms.domain.model.UserRole
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Operational endpoints. The grace-expiry job also runs on a daily schedule. */
fun Route.jobRoutes(expireGrace: ExpireGracePeriodAccountsUseCase) {
    route("/admin/jobs") {
        post("/expire-grace") {
            call.authorize(UserRole.SYSADMIN)
            call.ok(mapOf("expired" to expireGrace()))
        }
    }
}
