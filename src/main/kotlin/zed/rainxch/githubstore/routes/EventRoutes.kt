package zed.rainxch.githubstore.routes

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Telemetry was killed in the 2026-04 audit. Endpoint accepts and silently
// discards the batch — returns 204 No Content so pre-1.8.3 clients (which
// treat any non-2xx as failure and retry) stop spamming origin and Sentry.
//
// The data goes nowhere: the Events table and SignalAggregationWorker are
// still wired up for historical rows, but no new ingestion happens here.
//
// Once 1.8.3+ has propagated and TelemetryRepositoryImpl on the client has
// shipped a sticky-disable-on-410 flag, flip this back to `410 Gone` with a
// proper JSON deprecation notice so laggard clients get a real signal.
fun Route.eventRoutes() {
    post("/events") {
        call.respond(HttpStatusCode.NoContent)
    }
}
