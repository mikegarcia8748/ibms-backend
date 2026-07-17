package com.puregoldbe.ibms

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/api/health") {
            call.respondText("OK")
        }
    }
}
