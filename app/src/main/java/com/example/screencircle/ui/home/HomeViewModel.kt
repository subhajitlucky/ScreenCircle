package com.example.screencircle.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.screencircle.data.repository.ScreenTimeRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class WeeklyData(
    val dayLabels: List<String>,
    val usageHours: List<Float>,
    val totalSeconds: Long,
    val averageSeconds: Long
)

/**
 * HomeViewModel - Uses UsageStatsManager (Digital Wellbeing data)
 * 
 * BENEFITS OF THIS APPROACH:
 * - ZERO battery usage (no background service!)
 * - Data is always accurate (same source as Digital Wellbeing)
 * - Syncs to Firebase only when app is opened
 * - Much simpler code
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScreenTimeRepository(application)
    
    private val _todayUsage = MutableLiveData<Long>()
    val todayUsage: LiveData<Long> = _todayUsage
    
    private val _weeklyData = MutableLiveData<WeeklyData>()
    val weeklyData: LiveData<WeeklyData> = _weeklyData
    
    private val _syncStatus = MutableLiveData<Boolean>()
    val syncStatus: LiveData<Boolean> = _syncStatus

    init {
        loadTodayUsage()
        loadWeeklyData()
    }
    
    fun loadTodayUsage() {
        viewModelScope.launch {
            val seconds = repository.getTodayScreenTime()
            _todayUsage.postValue(seconds)
            
            // Sync to Firebase when user opens app
            repository.syncToFirebase()
        }
    }
    
    fun loadWeeklyData() {
        viewModelScope.launch {
            val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
            val calendar = Calendar.getInstance()
            
            val weeklyMap = repository.getWeeklyScreenTime()
            
            val dayLabels = mutableListOf<String>()
            val usageHours = mutableListOf<Float>()
            var totalSeconds = 0L
            var daysWithData = 0
            
            // Process last 7 days
            for (i in 6 downTo 0) {
                calendar.time = Date()
                calendar.add(Calendar.DAY_OF_YEAR, -i)
                
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                val dayLabel = dayFormat.format(calendar.time)
                
                dayLabels.add(dayLabel)
                
                val seconds = weeklyMap[dateStr] ?: 0L
                usageHours.add(seconds / 3600f)
                
                totalSeconds += seconds
                if (seconds > 0) daysWithData++
            }
            
            val averageSeconds = if (daysWithData > 0) totalSeconds / daysWithData else 0L
            
            _weeklyData.postValue(WeeklyData(dayLabels, usageHours, totalSeconds, averageSeconds))
        }
    }
    
    fun syncNow() {
        viewModelScope.launch {
            val success = repository.syncToFirebase()
            _syncStatus.postValue(success)
            loadTodayUsage()
            loadWeeklyData()
        }
    }
}
