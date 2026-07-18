package com.puregoldbe.ibms.adapter.controller

import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.application.usecase.ListActivitiesUseCase
import io.ktor.server.request.*
import io.ktor.server.routing.*

/** Read-only audit log (any authenticated role). Filter by `entityId`; cursor-paginated. */
fun Route.activityRoutes(listActivities: ListActivitiesUseCase) {
    route("/activities") {
        get {
            call.authorize()
            val p = call.pageParams()
            call.ok(listActivities(call.request.queryParameters["entityId"], p.cursor, p.limit))
        }
    }
}
