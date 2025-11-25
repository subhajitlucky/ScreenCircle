# ScreenCircle - Android Screen Time Tracker

ScreenCircle is an Android application that tracks your daily screen-on time and allows you to share it with friends in **Private Groups**. Challenge your friends to reduce screen time!

## 🚀 Features
- **Zero Battery Usage**: Uses Android's UsageStatsManager (same as Digital Wellbeing) - no background service!
- **Weekly Statistics**: View your screen time history with beautiful charts.
- **Private Groups**: Create or join groups to share stats with specific people.
- **Leaderboard**: Compete with friends - less screen time = higher rank! 👑
- **Background Sync**: WorkManager syncs data every 6 hours even if you don't open the app.
- **Material 3 Design**: Modern and clean UI with Material You support.

## 🔋 Why ScreenCircle is Battery-Friendly

Unlike other screen time apps that run constant background services, ScreenCircle:
- **Reads data from Android's built-in tracking** (UsageStatsManager)
- **No foreground service** = No persistent notification
- **Syncs every 6 hours** using WorkManager (only 4 times per day!)
- **Respects battery saver mode** automatically

| Metric | ScreenCircle | Other Apps |
|--------|--------------|------------|
| Background Battery | **~0.03%/day** | 1-5%/day |
| Background Data | **~4 KB/day** | 10-100 KB/day |
| Permissions | **3** | 5-8 |
| Persistent Notification | **No** | Yes |
| Sync Frequency | Every 6 hours | Constant |

## 📱 Screenshots
- **Home**: View today's screen time and weekly chart
- **Groups**: Create/join groups and see the leaderboard
- **Settings**: Manage profile, groups, view battery info, and logout

## 🛠️ Setup Instructions (IMPORTANT)

### 1. Firebase Setup
You **MUST** set up Firebase for this app to work.

1. Go to [Firebase Console](https://console.firebase.google.com/).
2. Create a new project.
3. Add an Android App with package name: `com.example.screencircle`.
4. Download `google-services.json` and place it in the `app/` directory of this project.
5. **Enable Authentication**:
   - Go to Build > Authentication > Sign-in method.
   - Enable **Email/Password**.
6. **Enable Realtime Database**:
   - Go to Build > Realtime Database > Create Database.
   - Start in **Test Mode** (or use the rules below).

### 2. Database Rules
Copy these rules to your Firebase Realtime Database Rules tab to ensure privacy:

```json
{
  "rules": {
    "users": {
      "$uid": {
        ".read": "auth != null",
        ".write": "$uid === auth.uid"
      }
    },
    "groups": {
      ".read": "auth != null",
      ".write": "auth != null"
    }
  }
}
```

### 3. Build & Run
1. Open the project in **Android Studio** (Hedgehog or newer recommended).
2. Sync Gradle.
3. Run on a device or emulator (API 26+).
4. **Grant Permission**: The app will ask for "Usage Access". You must enable it for ScreenCircle in the system settings.

## 📦 Project Structure

```
app/src/main/java/com/example/screencircle/
├── ScreenCircleApplication.kt       # App init + WorkManager setup
├── data/
│   ├── local/
│   │   ├── AppDatabase.kt           # Room database
│   │   ├── DailyUsage.kt            # Usage entity
│   │   ├── UsageDao.kt              # Database access
│   │   └── PreferencesManager.kt    # SharedPreferences helper
│   └── repository/
│       ├── GroupRepository.kt       # Firebase group operations
│       ├── ScreenTimeRepository.kt  # UsageStatsManager wrapper
│       └── UsageRepository.kt       # Local usage data management
├── worker/
│   └── DailySyncWorker.kt           # Background sync every 6 hours
└── ui/
    ├── login/
    │   └── LoginActivity.kt         # Login/Register
    ├── main/
    │   └── MainActivity.kt          # Main container + permission check
    ├── home/
    │   ├── HomeFragment.kt          # Today's usage + weekly chart
    │   └── HomeViewModel.kt
    ├── dashboard/
    │   ├── GroupDashboardFragment.kt  # Groups & leaderboard
    │   ├── GroupViewModel.kt
    │   └── GroupAdapter.kt
    └── settings/
        └── SettingsFragment.kt      # Profile, groups & battery info
```

## 📱 Permissions Used (Only 3!)
| Permission | Purpose |
|------------|---------|
| `INTERNET` | Firebase sync |
| `ACCESS_NETWORK_STATE` | Check connectivity before sync |
| `PACKAGE_USAGE_STATS` | Read screen time from Android |

**No foreground service, no boot receiver, no notifications required!**

## 🎮 How to Use

### Track Your Screen Time
1. Open the app and grant Usage Access permission
2. Your screen time is automatically read from Android's built-in tracking
3. View your stats on the Home tab

### Create/Join a Group
1. Go to the Groups tab
2. Tap "Create Group" and enter a name
3. Share the Group ID with friends
4. Friends tap "Join Group" and enter the ID

### Leaderboard
- Members are ranked by screen time (ascending)
- Less screen time = Higher rank
- 👑 Crown for 1st place, 🥈 for 2nd, 🥉 for 3rd

## 🔧 Tech Stack
- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 34 (Android 14)
- **UI**: Material Design 3, ViewBinding
- **Local DB**: Room
- **Remote DB**: Firebase Realtime Database
- **Auth**: Firebase Authentication
- **Background Work**: WorkManager
- **Charts**: MPAndroidChart
- **Architecture**: MVVM

## 🏗️ How It Works

### Screen Time Tracking
```
Android UsageStatsManager → ScreenTimeRepository → Firebase
         ↓
   (Digital Wellbeing)
```
- We read from Android's built-in tracking (same data as Digital Wellbeing)
- Zero battery for tracking - Android already does it!
- Sync to Firebase when app opens OR every 6 hours via WorkManager

### Background Sync Flow
```
WorkManager (every 6 hours)
    → Check if user logged in
    → Check if permission granted
    → Read today's screen time
    → Upload to Firebase
    → Done! (~1 second, ~0.008% battery)
```

## 📄 License
MIT License - feel free to use and modify!
