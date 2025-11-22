package com.example.screencircle.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.screencircle.data.local.AppDatabase
import com.example.screencircle.data.local.DailyUsage
import com.example.screencircle.data.repository.UsageRepository
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: UsageRepository
    val todayUsage: LiveData<DailyUsage?>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = UsageRepository(database.usageDao())
        
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        todayUsage = repository.getUsageForDate(today)
    }
    
    fun syncNow() {
        // Trigger sync manually if needed, though repository handles it on add
    }
}
