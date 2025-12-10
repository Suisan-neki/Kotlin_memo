package com.example

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.server.routing.*
import kotlinx.datetime.*

private val repo = InMemoryRepository()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        configureSerialization()
        configureRouting()
    }.start(wait = true)
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}

fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        route("/wage") {
            get {
                val wage = repo.getWage()
                if (wage == null) {
                    call.respondError(HttpStatusCode.NotFound, "Wage is not set")
                } else {
                    call.respond(wage)
                }
            }
            post {
                val req = runCatching { call.receive<WageUpdateRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid request body")
                    return@post
                }
                if (req.hourlyWage <= 0) {
                    call.respondError(HttpStatusCode.BadRequest, "hourlyWage must be positive")
                    return@post
                }
                val updated = repo.updateWage(req.hourlyWage)
                call.respond(updated)
            }
        }

        route("/sessions") {
            get {
                val dateParam = call.request.queryParameters["date"]
                val monthParam = call.request.queryParameters["month"]
                val tz = TimeZone.UTC
                val date = dateParam?.let { parseDateOrNull(it) }
                val ym = monthParam?.let { parseYearMonthOrNull(it) }
                if (dateParam != null && date == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid date format. Expected YYYY-MM-DD")
                    return@get
                }
                if (monthParam != null && ym == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid month format. Expected YYYY-MM")
                    return@get
                }
                val list = repo.listSessions(date = date, yearMonth = ym, tz = tz)
                call.respond(list)
            }

            get("/current") {
                val current = repo.getCurrentSession()
                if (current == null) call.respondError(HttpStatusCode.NotFound, "No active session")
                else call.respond(current)
            }

            post("/start") {
                data class StartBody(val hourlyWageOverride: Int? = null)
                val body = runCatching { call.receive<StartBody>() }.getOrNull()
                val session = repo.startSession(body?.hourlyWageOverride)
                if (session == null) {
                    // determine reason: either already active or wage not set
                    // If an active exists, return session already started else wage not set
                    val current = repo.getCurrentSession()
                    if (current != null) {
                        call.respondError(HttpStatusCode.BadRequest, "session already started")
                    } else {
                        call.respondError(HttpStatusCode.BadRequest, "hourly wage is not set")
                    }
                } else {
                    call.respond(HttpStatusCode.Created, session)
                }
            }

            post("{id}/stop") {
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid id")
                    return@post
                }
                val result = repo.stopSession(id)
                val value = result.getOrNull()
                if (value != null) {
                    call.respond(value)
                } else {
                    val ex = result.exceptionOrNull()
                    when (ex) {
                        is NoSuchElementException -> call.respondError(HttpStatusCode.NotFound, "Session not found")
                        is IllegalStateException -> call.respondError(HttpStatusCode.BadRequest, "Session already stopped")
                        else -> call.respondError(HttpStatusCode.InternalServerError, "Unknown error")
                    }
                }
            }
        }

        route("/summary") {
            get("/daily") {
                val dateParam = call.request.queryParameters["date"]
                val parsed = dateParam?.let { parseDateOrNull(it) }
                if (dateParam != null && parsed == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid date format. Expected YYYY-MM-DD")
                    return@get
                }
                val date = parsed ?: Clock.System.now().toLocalDateTime(TimeZone.UTC).date
                val resp = repo.dailySummary(date)
                call.respond(resp)
            }
            get("/monthly") {
                val yearStr = call.request.queryParameters["year"]
                val monthStr = call.request.queryParameters["month"]
                val year = yearStr?.toIntOrNull()
                val month = monthStr?.toIntOrNull()
                if (year == null || month == null) {
                    call.respondError(HttpStatusCode.BadRequest, "year and month are required")
                    return@get
                }
                if (month !in 1..12) {
                    call.respondError(HttpStatusCode.BadRequest, "month must be 01-12")
                    return@get
                }
                val resp = repo.monthlySummary(year, month)
                call.respond(resp)
            }
        }
    }
}

private fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, ErrorResponse(message))
}

private fun parseDateOrNull(s: String): LocalDate? = runCatching {
    val parts = s.split("-")
    if (parts.size != 3) return null
    val y = parts[0].toInt()
    val m = parts[1].toInt()
    val d = parts[2].toInt()
    LocalDate(y, m, d)
}.getOrNull()

private fun parseYearMonthOrNull(s: String): YearMonth? = runCatching {
    val parts = s.split("-")
    if (parts.size != 2) return null
    val y = parts[0].toInt()
    val m = parts[1].toInt()
    if (m !in 1..12) return null
    YearMonth(y, m)
}.getOrNull()
