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
import io.ktor.server.http.content.*
import kotlinx.datetime.*

private val repo = SqlRepository()

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    // Initialize database (DATA_DIR/app.db)
    Database.init()
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
        // Serve static web UI
        get("/") {
            call.respondRedirect("/index.html")
        }
        staticResources("/", "static")

        // Auth
        route("/auth") {
            post("/login") {
                val body = runCatching { call.receive<LoginRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid request body")
                    return@post
                }
                val userId = body.userId.trim()
                if (!isValidUserId(userId)) {
                    call.respondError(HttpStatusCode.BadRequest, "invalid userId")
                    return@post
                }
                // ensure user exists
                repo.ensureUser(userId)
                call.response.cookies.append(
                    Cookie(
                        name = "userId",
                        value = userId,
                        httpOnly = true,
                        path = "/",
                        maxAge = 15552000,
                        extensions = mapOf("SameSite" to "Lax")
                    )
                )
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            }
            post("/logout") {
                call.response.cookies.append(
                    Cookie(name = "userId", value = "", path = "/", maxAge = 0, httpOnly = true, extensions = mapOf("SameSite" to "Lax"))
                )
                call.respond(HttpStatusCode.OK, mapOf("ok" to true))
            }
        }

        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }

        route("/wage") {
            get {
                val userId = call.userIdOr401() ?: return@get
                val wage = repo.getWage(userId)
                if (wage == null) {
                    call.respondError(HttpStatusCode.NotFound, "Wage is not set")
                } else {
                    call.respond(wage)
                }
            }
            post {
                val userId = call.userIdOr401() ?: return@post
                val req = runCatching { call.receive<WageUpdateRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid request body")
                    return@post
                }
                if (req.hourlyWage <= 0) {
                    call.respondError(HttpStatusCode.BadRequest, "hourlyWage must be positive")
                    return@post
                }
                val updated = repo.updateWage(userId, req.hourlyWage)
                call.respond(updated)
            }
        }

        route("/sessions") {
            get {
                val userId = call.userIdOr401() ?: return@get
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
                val list = repo.listSessions(userId = userId, date = date, yearMonth = ym, tz = tz)
                call.respond(list)
            }

            get("/current") {
                val userId = call.userIdOr401() ?: return@get
                val current = repo.getCurrentSession(userId)
                if (current == null) call.respondError(HttpStatusCode.NotFound, "No active session")
                else call.respond(current)
            }

            post("/start") {
                val userId = call.userIdOr401() ?: return@post
                val body = runCatching { call.receive<StartSessionRequest>() }.getOrNull()
                val result = repo.startSession(userId, body?.hourlyWageOverride)
                val value = result.getOrNull()
                if (value != null) call.respond(HttpStatusCode.Created, value) else {
                    val msg = result.exceptionOrNull()?.message
                    when (msg) {
                        "session already started" -> call.respondError(HttpStatusCode.BadRequest, "session already started")
                        "hourly wage is not set" -> call.respondError(HttpStatusCode.BadRequest, "hourly wage is not set")
                        else -> call.respondError(HttpStatusCode.InternalServerError, "Unknown error")
                    }
                }
            }

            post("{id}/stop") {
                val userId = call.userIdOr401() ?: return@post
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid id")
                    return@post
                }
                val result = repo.stopSession(userId, id)
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

            // Edit and Delete
            put("{id}") {
                val userId = call.userIdOr401() ?: return@put
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) { call.respondError(HttpStatusCode.BadRequest, "Invalid id"); return@put }
                val body = runCatching { call.receive<UpdateSessionRequest>() }.getOrElse {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid request body"); return@put
                }
                if (body.startTime == null && body.endTime == null) { call.respondError(HttpStatusCode.BadRequest, "startTime or endTime required"); return@put }
                val start = body.startTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                val end = body.endTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                if (body.startTime != null && start == null) { call.respondError(HttpStatusCode.BadRequest, "Invalid startTime"); return@put }
                if (body.endTime != null && end == null) { call.respondError(HttpStatusCode.BadRequest, "Invalid endTime"); return@put }
                val result = repo.updateSession(userId, id, start, end)
                val value = result.getOrNull()
                if (value != null) call.respond(value) else {
                    val ex = result.exceptionOrNull()
                    when (ex) {
                        is NoSuchElementException -> call.respondError(HttpStatusCode.NotFound, "Session not found")
                        is IllegalArgumentException -> call.respondError(HttpStatusCode.BadRequest, ex.message ?: "Bad request")
                        else -> call.respondError(HttpStatusCode.InternalServerError, "Unknown error")
                    }
                }
            }
            delete("{id}") {
                val userId = call.userIdOr401() ?: return@delete
                val id = call.parameters["id"]?.toIntOrNull()
                if (id == null) { call.respondError(HttpStatusCode.BadRequest, "Invalid id"); return@delete }
                val ok = repo.deleteSession(userId, id)
                if (!ok) call.respondError(HttpStatusCode.NotFound, "Session not found") else call.respond(HttpStatusCode.NoContent, Unit)
            }
        }

        route("/summary") {
            get("/daily") {
                val userId = call.userIdOr401() ?: return@get
                val dateParam = call.request.queryParameters["date"]
                val parsed = dateParam?.let { parseDateOrNull(it) }
                if (dateParam != null && parsed == null) {
                    call.respondError(HttpStatusCode.BadRequest, "Invalid date format. Expected YYYY-MM-DD")
                    return@get
                }
                val date = parsed ?: Clock.System.now().toLocalDateTime(TimeZone.UTC).date
                val resp = repo.dailySummary(userId, date)
                call.respond(resp)
            }
            get("/monthly") {
                val userId = call.userIdOr401() ?: return@get
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
                val resp = repo.monthlySummary(userId, year, month)
                call.respond(resp)
            }
        }
    }
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, ErrorResponse(message))
}

private suspend fun ApplicationCall.userIdOr401(): String? {
    val uid = request.cookies["userId"]?.trim()
    if (uid.isNullOrBlank()) {
        respondError(HttpStatusCode.Unauthorized, "login required")
        return null
    }
    return uid
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

private fun isValidUserId(s: String): Boolean {
    if (s.isEmpty() || s.length > 64) return false
    return s.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
