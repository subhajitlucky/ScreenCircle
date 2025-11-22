package com.example.screencircle.data.local

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UsageDao {
    @Query("SELECT * FROM daily_usage WHERE date = :date")
    fun getUsageForDate(date: String): LiveData<DailyUsage?>

    @Query("SELECT * FROM daily_usage WHERE date = :date")
    suspend fun getUsageForDateSync(date: String): DailyUsage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(usage: DailyUsage)

    @Query("SELECT * FROM daily_usage")
    fun getAllUsage(): LiveData<List<DailyUsage>>
}
