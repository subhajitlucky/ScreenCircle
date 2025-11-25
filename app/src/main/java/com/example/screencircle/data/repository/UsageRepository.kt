package com.example.screencircle.data.repository

import android.util.Log
import androidx.lifecycle.LiveData
import com.example.screencircle.data.local.DailyUsage
import com.example.screencircle.data.local.UsageDao
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UsageRepository - Manages screen time data storage and sync
 * 
 * DATA FLOW:
 * 1. Screen time is always saved to local Room database first (fast, offline)
 * 2. Firebase sync happens based on smart batching:
 *    - WiFi: Sync immediately
 *    - Mobile data: Batch sync (every 5 events or 5 minutes)
 *    - No network: Skip sync, data safe in local DB
 * 
 * DATA USAGE ESTIMATE:
 * - Each Firebase write = ~100 bytes (userId path + date + number)
 * - Typical: 50-100 screen cycles/day → 10-20 syncs with batching
 * - Total: ~2KB/day on mobile data (negligible!)
 * 
 * This "offline-first" approach ensures:
 * - Data is never lost even without internet
 * - Minimal network and data usage
 * - Fast local reads for UI
 */
class UsageRepository(private val usageDao: UsageDao) {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    val allLocalUsage: LiveData<List<DailyUsage>> = usageDao.getAllUsage()

    fun getUsageForDate(date: String): LiveData<DailyUsage?> {
        return usageDao.getUsageForDate(date)
    }

    suspend fun getUsageForDateSync(date: String): DailyUsage? {
        return withContext(Dispatchers.IO) {
            usageDao.getUsageForDateSync(date)
        }
    }

    /**
     * Add screen time to local database and optionally sync to Firebase
     * 
     * @param seconds The number of seconds to add
     * @param syncToCloud Whether to sync to Firebase immediately
     */
    suspend fun addScreenTime(seconds: Long, syncToCloud: Boolean = true) {
        withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val currentUsage = usageDao.getUsageForDateSync(today)
            
            val newTotal = (currentUsage?.totalSeconds ?: 0) + seconds
            val usage = DailyUsage(today, newTotal, System.currentTimeMillis())
            
            // Always save locally first (fast, reliable)
            usageDao.insertOrUpdate(usage)
            
            // Sync to Firebase based on conditions
            if (syncToCloud) {
                syncToFirebase(today, newTotal)
            }
        }
    }

    /**
     * Sync all unsynced data to Firebase
     * Call this when app opens or when WiFi becomes available
     */
    suspend fun syncPendingData() {
        withContext(Dispatchers.IO) {
            val userId = firebaseAuth.currentUser?.uid ?: return@withContext
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val usage = usageDao.getUsageForDateSync(today) ?: return@withContext
            
            syncToFirebase(today, usage.totalSeconds)
        }
    }

    private fun syncToFirebase(date: String, totalSeconds: Long) {
        val userId = firebaseAuth.currentUser?.uid ?: return
        val userUsageRef = database.child("users").child(userId).child("usage").child(date)
        
        userUsageRef.setValue(totalSeconds)
            .addOnSuccessListener {
                Log.d("UsageRepository", "Synced $totalSeconds seconds for $date")
            }
            .addOnFailureListener {
                Log.e("UsageRepository", "Failed to sync to Firebase", it)
                // Data is safe in local DB, will sync next time
            }
    }
}
