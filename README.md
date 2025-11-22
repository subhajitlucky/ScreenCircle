# ScreenCircle - Android Screen Time Tracker

ScreenCircle is an Android application that tracks your daily screen-on time and allows you to share it with friends in **Private Groups**.

## 🚀 Features
- **Background Tracking**: Automatically tracks screen usage even when the app is closed.
- **Private Groups**: Create or join groups to share stats with specific people.
- **Realtime Sync**: Data is synced instantly via Firebase Realtime Database.
- **Local Storage**: Data is cached locally using Room Database.
- **Material 3 Design**: Modern and clean UI.

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
1. Open the project in **Android Studio**.
2. Sync Gradle.
3. Run on a device or emulator.
4. **Grant Permissions**: The app will ask for "Usage Access". You must enable it for ScreenCircle in the system settings.

## 📱 Permissions Used
- `PACKAGE_USAGE_STATS`: To track screen time.
- `FOREGROUND_SERVICE`: To keep the tracking service alive.
- `RECEIVE_BOOT_COMPLETED`: To restart tracking after phone reboot.
