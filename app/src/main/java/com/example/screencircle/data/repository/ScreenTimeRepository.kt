package com.example.screencircle.data.repository

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * ScreenTimeRepository - Uses Android's UsageStatsManager (Digital Wellbeing data)
 * 
 * WHY THIS APPROACH IS BETTER:
 * 1. ZERO battery usage - Android already tracks this, we just read it
 * 2. ZERO background service needed - no foreground notification!
 * 3. MORE accurate - uses the same data as Digital Wellbeing
 * 4. SIMPLER code - just query the system API
 * 5. Works even when app is killed
 * 
 * HOW IT WORKS:
 * - Android tracks all screen on/off events in UsageStatsManager
 * - We query this data when user opens the app
 * - We sync to Firebase only when app is opened (user-triggered)
 * - This uses ~0 battery and ~0 background data!
 * 
 * SYNC STRATEGY:
 * - Sync happens only when user opens the app
 * - This is perfect because:
 *   - Friends see updated data when you're active
 *   - Zero background battery/data usage
 *   - If user doesn't open app for days, they probably don't care about groups
 */
class ScreenTimeRepository(private val context: Context) {

    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabase.getInstance().reference
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * Get today's total screen time in seconds
     * Reads from Android's built-in tracking (same as Digital Wellbeing)
     */
    suspend fun getTodayScreenTime(): Long = withContext(Dispatchers.IO) {
        getScreenTimeForDate(Date())
    }

    /**
     * Get screen time for a specific date
     */
    suspend fun getScreenTimeForDate(date: Date): Long = withContext(Dispatchers.IO) {
        val calendar = Calendar.getInstance().apply {
            time = date
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis
        
        calendar.add(Calendar.DAY_OF_YEAR, 1)
        val endTime = calendar.timeInMillis

        calculateScreenTime(startTime, endTime)
    }

    /**
     * Get screen time for last 7 days
     * Returns map of date string to seconds
     */
    suspend fun getWeeklyScreenTime(): Map<String, Long> = withContext(Dispatchers.IO) {
        val result = mutableMapOf<String, Long>()
        val calendar = Calendar.getInstance()
        
        for (i in 6 downTo 0) {
            calendar.time = Date()
            calendar.add(Calendar.DAY_OF_YEAR, -i)
            
            val dateStr = dateFormat.format(calendar.time)
            val seconds = getScreenTimeForDate(calendar.time)
            result[dateStr] = seconds
        }
        
        result
    }

    /**
     * Calculate actual screen-on time using UsageStats
     * This matches Digital Wellbeing by summing foreground time of all apps
     */
    private fun calculateScreenTime(startTime: Long, endTime: Long): Long {
        var totalScreenTime = 0L

        try {
            // Use INTERVAL_DAILY for better accuracy
            val usageStatsList = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

            // Sum up foreground time for all apps (same as Digital Wellbeing)
            for (usageStats in usageStatsList) {
                totalScreenTime += usageStats.totalTimeInForeground
            }

            // Convert from milliseconds to seconds
            totalScreenTime /= 1000

        } catch (e: Exception) {
            Log.e("ScreenTimeRepo", "Error getting screen time", e)
        }

        return totalScreenTime
    }

    /**
     * Sync today's screen time to Firebase
     * Called when user opens the app - no background syncing needed!
     */
    suspend fun syncToFirebase(): Boolean = withContext(Dispatchers.IO) {
        val userId = firebaseAuth.currentUser?.uid ?: return@withContext false
        val today = dateFormat.format(Date())
        val todaySeconds = getTodayScreenTime()

        try {
            database.child("users").child(userId).child("usage").child(today)
                .setValue(todaySeconds)
            Log.d("ScreenTimeRepo", "Synced $todaySeconds seconds for $today")
            true
        } catch (e: Exception) {
            Log.e("ScreenTimeRepo", "Sync failed", e)
            false
        }
    }

    /**
     * Check if we have permission to read usage stats
     */
    fun hasUsageStatsPermission(): Boolean {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            calendar.timeInMillis,
            System.currentTimeMillis()
        )
        return stats != null && stats.isNotEmpty()
    }
}
