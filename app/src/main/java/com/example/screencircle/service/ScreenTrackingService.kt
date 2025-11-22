package com.example.screencircle.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.screencircle.R
import com.example.screencircle.data.local.AppDatabase
import com.example.screencircle.data.repository.UsageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ScreenTrackingService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private lateinit var repository: UsageRepository
    private var screenOnTime: Long = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOnTime = System.currentTimeMillis()
                    Log.d("ScreenTracking", "Screen ON at $screenOnTime")
                }
                Intent.ACTION_SCREEN_OFF -> {
                    val screenOffTime = System.currentTimeMillis()
                    if (screenOnTime > 0) {
                        val duration = (screenOffTime - screenOnTime) / 1000
                        Log.d("ScreenTracking", "Screen OFF. Duration: $duration seconds")
                        if (duration > 0) {
                            scope.launch {
                                repository.addScreenTime(duration)
                            }
                        }
                    }
                    screenOnTime = 0
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(applicationContext)
        repository = UsageRepository(database.usageDao())
        
        startForegroundService()
        registerScreenReceiver()
        
        // Initialize screenOnTime if screen is already on
        // Note: This is a simplification. Ideally we check DisplayManager.
        screenOnTime = System.currentTimeMillis()
    }

    private fun startForegroundService() {
        val channelId = "ScreenCircleTracking"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Tracking Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ScreenCircle is Active")
            .setContentText("Tracking your screen time...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Make sure this exists or use system icon
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, filter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenReceiver)
        job.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
