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

class UsageRepository(private val usageDao: UsageDao) {

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database: DatabaseReference = FirebaseDatabase.getInstance().reference

    val allLocalUsage: LiveData<List<DailyUsage>> = usageDao.getAllUsage()

    fun getUsageForDate(date: String): LiveData<DailyUsage?> {
        return usageDao.getUsageForDate(date)
    }

    suspend fun addScreenTime(seconds: Long) {
        withContext(Dispatchers.IO) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val currentUsage = usageDao.getUsageForDateSync(today)
            
            val newTotal = (currentUsage?.totalSeconds ?: 0) + seconds
            val usage = DailyUsage(today, newTotal, System.currentTimeMillis())
            
            usageDao.insertOrUpdate(usage)
            syncToFirebase(today, newTotal)
        }
    }

    private fun syncToFirebase(date: String, totalSeconds: Long) {
        val userId = firebaseAuth.currentUser?.uid ?: return
        val userUsageRef = database.child("users").child(userId).child("usage").child(date)
        userUsageRef.setValue(totalSeconds).addOnFailureListener {
            Log.e("UsageRepository", "Failed to sync to Firebase", it)
        }
    }
}
