package com.example

import kotlinx.datetime.*
import kotlin.math.floor

class InMemoryRepository {
    @Volatile
    private var currentWage: WageSetting? = null
    private val sessions = mutableListOf<InternalSession>()
    private var nextId = 1

    data class InternalSession(
        val id: Int,
        val start: Instant,
        var end: Instant? = null,
        val hourlyWage: Int,
        var earnedAmount: Int? = null
    )

    private val lock = Any()

    fun getWage(): WageSetting? = currentWage

    fun updateWage(hourlyWage: Int, now: Instant = Clock.System.now()): WageSetting {
        val updated = WageSetting(hourlyWage, now.toString())
        currentWage = updated
        return updated
    }

    fun startSession(overrideWage: Int?, now: Instant = Clock.System.now()): WorkSession? {
        synchronized(lock) {
            if (sessions.any { it.end == null }) return null
            val wage = overrideWage ?: currentWage?.hourlyWage ?: return null
            val ses = InternalSession(id = nextId++, start = now, hourlyWage = wage)
            sessions += ses
            return ses.toDto()
        }
    }

    fun stopSession(id: Int, now: Instant = Clock.System.now()): Result<WorkSession> {
        synchronized(lock) {
            val ses = sessions.find { it.id == id } ?: return Result.failure(NoSuchElementException("not found"))
            if (ses.end != null) return Result.failure(IllegalStateException("already stopped"))
            ses.end = now
            val seconds = (ses.end!! - ses.start).inWholeSeconds
            val earned = floor(ses.hourlyWage * (seconds / 3600.0)).toInt()
            ses.earnedAmount = earned
            return Result.success(ses.toDto())
        }
    }

    fun getCurrentSession(now: Instant = Clock.System.now()): CurrentSessionResponse? {
        val ses = synchronized(lock) { sessions.find { it.end == null } } ?: return null
        val elapsed = (now - ses.start).inWholeSeconds
        val currentEarned = floor(ses.hourlyWage * (elapsed / 3600.0)).toInt()
        return CurrentSessionResponse(
            id = ses.id,
            startTime = ses.start.toString(),
            hourlyWage = ses.hourlyWage,
            elapsedSeconds = elapsed,
            currentEarnedAmount = currentEarned
        )
    }

    fun listSessions(date: LocalDate? = null, yearMonth: YearMonth? = null, tz: TimeZone = TimeZone.UTC): List<WorkSession> {
        val list = synchronized(lock) { sessions.map { it.toDto() } }
        return when {
            date != null -> list.filter { s ->
                val startDate = Instant.parse(s.startTime).toLocalDateTime(tz).date
                val endDate = s.endTime?.let { Instant.parse(it).toLocalDateTime(tz).date }
                startDate == date || endDate == date
            }
            yearMonth != null -> list.filter { s ->
                val startYm = Instant.parse(s.startTime).toLocalDateTime(tz).date.let { YearMonth(it.year, it.monthNumber) }
                val endYm = s.endTime?.let { Instant.parse(it).toLocalDateTime(tz).date.let { d -> YearMonth(d.year, d.monthNumber) } }
                startYm == yearMonth || endYm == yearMonth
            }
            else -> list
        }
    }

    fun dailySummary(date: LocalDate, tz: TimeZone = TimeZone.UTC): DailySummaryResponse {
        val list = synchronized(lock) { sessions.toList() }
        val total = list.filter { it.end != null }
            .filter { s ->
                val startDate = s.start.toLocalDateTime(tz).date
                val endDate = s.end!!.toLocalDateTime(tz).date
                startDate == date || endDate == date
            }
            .sumOf { it.earnedAmount ?: 0 }
        return DailySummaryResponse(date.toString(), total)
    }

    fun monthlySummary(year: Int, month: Int, tz: TimeZone = TimeZone.UTC): MonthlySummaryResponse {
        val ym = YearMonth(year, month)
        val list = synchronized(lock) { sessions.toList() }
        val completed = list.filter { it.end != null }
            .filter { s ->
                val endDate = s.end!!.toLocalDateTime(tz).date
                endDate.year == year && endDate.monthNumber == month
            }
        val byDay = completed.groupBy { it.end!!.toLocalDateTime(tz).date }
            .toSortedMap()
            .map { (date, items) ->
                MonthlyDailyBreakdownItem(date.toString(), items.sumOf { it.earnedAmount ?: 0 })
            }
        val total = byDay.sumOf { it.earnedAmount }
        return MonthlySummaryResponse(year = ym.year, month = ym.month, totalEarnedAmount = total, dailyBreakdown = byDay)
    }

    private fun InternalSession.toDto(): WorkSession = WorkSession(
        id = id,
        startTime = start.toString(),
        endTime = end?.toString(),
        hourlyWage = hourlyWage,
        earnedAmount = earnedAmount
    )
}

data class YearMonth(val year: Int, val month: Int)
