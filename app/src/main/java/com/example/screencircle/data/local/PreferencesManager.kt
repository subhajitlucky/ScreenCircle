package com.example.screencircle.data.local

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    var currentGroupId: String?
        get() = prefs.getString(KEY_GROUP_ID, null)
        set(value) = prefs.edit().putString(KEY_GROUP_ID, value).apply()
    
    var currentGroupName: String?
        get() = prefs.getString(KEY_GROUP_NAME, null)
        set(value) = prefs.edit().putString(KEY_GROUP_NAME, value).apply()
    
    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()
    
    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()
    
    fun clearGroupData() {
        prefs.edit()
            .remove(KEY_GROUP_ID)
            .remove(KEY_GROUP_NAME)
            .apply()
    }
    
    fun clearAll() {
        prefs.edit().clear().apply()
    }
    
    companion object {
        private const val PREFS_NAME = "screencircle_prefs"
        private const val KEY_GROUP_ID = "current_group_id"
        private const val KEY_GROUP_NAME = "current_group_name"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_EMAIL = "user_email"
        
        @Volatile
        private var INSTANCE: PreferencesManager? = null
        
        fun getInstance(context: Context): PreferencesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PreferencesManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
