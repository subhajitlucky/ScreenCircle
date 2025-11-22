package com.example.screencircle.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_usage")
data class DailyUsage(
    @PrimaryKey val date: String, // Format: YYYY-MM-DD
    val totalSeconds: Long,
    val lastSyncedTimestamp: Long = 0
)
