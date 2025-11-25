package com.example.screencircle.ui.main

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.screencircle.R
import com.example.screencircle.databinding.ActivityMainBinding
import com.example.screencircle.ui.dashboard.GroupDashboardFragment
import com.example.screencircle.ui.home.HomeFragment
import com.example.screencircle.ui.settings.SettingsFragment

/**
 * MainActivity - No background service needed!
 * 
 * We use UsageStatsManager (Digital Wellbeing) instead of our own tracking.
 * This means:
 * - No foreground service
 * - No notification
 * - Zero battery usage
 * - Just need Usage Access permission
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()

        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> loadFragment(HomeFragment())
                R.id.nav_dashboard -> loadFragment(GroupDashboardFragment())
                R.id.nav_settings -> loadFragment(SettingsFragment())
            }
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun checkPermissions() {
        // Only need Usage Access Permission - that's it!
        if (!hasUsageStatsPermission()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_required)
                .setMessage(R.string.permission_desc)
                .setPositiveButton(R.string.grant_permission) { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setCancelable(false)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if permission was granted after returning from settings
        if (!hasUsageStatsPermission()) {
            checkPermissions()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
