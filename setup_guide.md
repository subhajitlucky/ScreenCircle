# 🛠️ Complete Setup Guide for ScreenCircle

Since you are new to Android development, here is a step-by-step guide to getting this app running on your phone.

## 1. Install Android Studio (REQUIRED)
Yes, you **must** install Android Studio. It is the only official tool to build and run Android apps.

1.  **Download**: Go to [developer.android.com/studio](https://developer.android.com/studio).
2.  **Install on Linux**:
    *   Download the `.tar.gz` file.
    *   Extract it to a folder (e.g., `~/android-studio`).
    *   Open a terminal, go to `android-studio/bin`, and run `./studio.sh`.
    *   Follow the setup wizard (Standard installation is fine). It will download the Android SDK and tools.

## 2. Open the Project
1.  Launch **Android Studio**.
2.  Click **Open**.
3.  Navigate to this folder:
    `/home/subhajitlucky/project/ScreenCircle`
4.  Click **OK**.
5.  **Wait**: Android Studio will start "syncing" the project. This downloads all the libraries (Firebase, Room, etc.). It might take 5-10 minutes the first time.

## 3. Firebase Setup (CRITICAL)
The app will **crash** if you skip this.

1.  Go to [Firebase Console](https://console.firebase.google.com/).
2.  Click **Add Project** -> Name it "ScreenCircle".
3.  Click the **Android Icon** to add an app.
4.  **Package Name**: `com.example.screencircle` (Must match exactly).
5.  Click **Register App**.
6.  **Download google-services.json**.
7.  **Move the file**:
    *   Copy the downloaded `google-services.json` file.
    *   Paste it into the `app` folder of your project:
        `/home/subhajitlucky/project/ScreenCircle/app/google-services.json`

## 4. Run on Your Phone
1.  **Enable Developer Options** on your phone:
    *   Settings > About Phone.
    *   Tap **Build Number** 7 times until it says "You are a developer".
2.  **Enable USB Debugging**:
    *   Settings > System > Developer Options.
    *   Turn on **USB Debugging**.
3.  **Connect via USB**: Plug your phone into your computer.
4.  **Run in Android Studio**:
    *   Look at the top toolbar. You should see your phone's name (e.g., "Pixel 6").
    *   Click the green **Play ▶️** button.

## 5. Final Steps
1.  The app will install and open.
2.  **Login**: Create an account with email/password.
3.  **Permissions**: The app will ask for "Usage Access".
    *   Tap "Grant Permission".
    *   Find "ScreenCircle" in the list.
    *   Toggle it **ON**.
4.  **Done!** Lock your phone and unlock it to start tracking.

## 6. How to Create an APK
Yes, you can create an APK file to share with friends!

### Option A: Quick Debug APK (For testing)
1.  Go to **Build** menu > **Build Bundle(s) / APK(s)** > **Build APK(s)**.
2.  Wait for the "Build Successful" message.
3.  Click **locate** in the popup (or go to `app/build/outputs/apk/debug/`).
4.  You will see `app-debug.apk`. You can send this to friends, but they might get a security warning.

### Option B: Signed APK (For Release)
1.  Go to **Build** > **Generate Signed Bundle / APK**.
2.  Choose **APK**.
3.  Create a new "Key Store" (password protected file to sign your app).
4.  Build.
5.  This produces a `release.apk` which is safer and ready for the Play Store.
