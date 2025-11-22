# How to Generate APKs in Android Studio

## 1. Generate a Debug APK
The Debug APK is for testing on your own device. It's not for the Play Store.

1.  Open **Android Studio**.
2.  Go to the top menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3.  Wait for the build to finish (check the bottom right status bar).
4.  A notification will appear: "APK(s) generated successfully".
5.  Click **locate** in that notification to open the folder containing `app-debug.apk`.
    *   *Alternatively, find it here:* `ScreenCircle/app/build/outputs/apk/debug/app-debug.apk`

---

## 2. Generate a Signed APK (Release)
The Signed APK is for publishing to the Play Store or sharing a production version. You need a "Keystore" file (a digital ID for your app).

### Step A: Start the Wizard
1.  Go to **Build > Generate Signed Bundle / APK...**.
2.  Select **APK** and click **Next**.

### Step B: Create a Keystore (If you don't have one)
1.  Under "Key store path", click **Create new...**.
2.  **Key store path**: Click the folder icon. Save it in your project folder (e.g., named `upload-keystore.jks`). **Do not delete this file!**
3.  **Password**: Create a strong password (e.g., `MyStrongPass123`). Confirm it.
4.  **Key > Alias**: You can leave it as `key0` or name it `upload`.
5.  **Key > Password**: Use the same password as above to keep it simple.
6.  **Certificate**: Fill in at least "First and Last Name" (e.g., `ScreenCircle Team`).
7.  Click **OK**.

### Step C: Build the APK
1.  Back in the wizard, the keystore info should be filled in.
2.  Check **Remember passwords** so you don't have to type them next time.
3.  Click **Next**.
4.  Select **release**.
5.  Click **Create**.
6.  Wait for the build to finish.
7.  Click **locate** in the notification to find `app-release.apk`.
    *   *Location:* `ScreenCircle/app/release/app-release.apk`
