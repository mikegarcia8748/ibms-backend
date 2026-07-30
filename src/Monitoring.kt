package com.puregoldbe.ibms

import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

/**
 * Installs request metrics and hands the registry back to the caller.
 *
 * The scrape route is deliberately *not* registered here. It exposes internal
 * request timings, URI templates and JVM details, so it belongs behind
 * authentication — and wrapping it in `authenticate(...)` at this point fails at
 * route-build time, because this runs before `configureAuthentication` has
 * registered a provider. The composition root owns the route instead; see
 * `moduleWith` in infrastructure/Bootstrap.kt.
 */
fun Application.configureMonitoring(): PrometheusMeterRegistry {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    install(MicrometerMetrics) {
        this.registry = registry
    }
    return registry
}
