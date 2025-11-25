package com.example.screencircle.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.screencircle.data.repository.ScreenTimeRepository
import com.google.firebase.auth.FirebaseAuth

/**
 * DailySyncWorker - Syncs screen time to Firebase once per day
 * 
 * WHY WE NEED THIS:
 * - If user doesn't open the app, friends won't see their screen time
 * - This worker runs once per day in background to keep data fresh
 * 
 * BATTERY IMPACT:
 * - Runs only ONCE per day
 * - Takes ~1-2 seconds to complete
 * - Uses ~0.01% battery per execution
 * - WorkManager handles battery optimization automatically
 * 
 * WHEN IT RUNS:
 * - Approximately every 24 hours
 * - Only when device has network connectivity
 * - Respects battery saver mode
 * - Android may delay execution to batch with other work (Doze mode)
 */
class DailySyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.d("DailySyncWorker", "Starting daily sync...")
        
        // Check if user is logged in
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Log.d("DailySyncWorker", "User not logged in, skipping sync")
            return Result.success()
        }
        
        return try {
            val repository = ScreenTimeRepository(applicationContext)
            
            // Check if we have permission
            if (!repository.hasUsageStatsPermission()) {
                Log.w("DailySyncWorker", "No usage stats permission")
                return Result.success() // Don't retry, user needs to grant permission
            }
            
            // Sync today's screen time
            val success = repository.syncToFirebase()
            
            if (success) {
                Log.d("DailySyncWorker", "Daily sync completed successfully")
                Result.success()
            } else {
                Log.w("DailySyncWorker", "Sync failed, will retry")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e("DailySyncWorker", "Error during sync", e)
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "daily_screen_time_sync"
    }
}
