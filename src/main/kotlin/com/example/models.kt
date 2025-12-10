package com.example

import kotlinx.serialization.Serializable

@Serializable
data class WageSetting(
    val hourlyWage: Int,
    val updatedAt: String
)

@Serializable
data class WorkSession(
    val id: Int,
    val startTime: String,
    val endTime: String? = null,
    val hourlyWage: Int,
    val earnedAmount: Int? = null
)

@Serializable
data class WageUpdateRequest(val hourlyWage: Int)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class CurrentSessionResponse(
    val id: Int,
    val startTime: String,
    val hourlyWage: Int,
    val elapsedSeconds: Long,
    val currentEarnedAmount: Int
)

@Serializable
data class DailySummaryResponse(
    val date: String,
    val totalEarnedAmount: Int
)

@Serializable
data class MonthlyDailyBreakdownItem(
    val date: String,
    val earnedAmount: Int
)

@Serializable
data class MonthlySummaryResponse(
    val year: Int,
    val month: Int,
    val totalEarnedAmount: Int,
    val dailyBreakdown: List<MonthlyDailyBreakdownItem>
)
