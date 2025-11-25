# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Firebase
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class com.example.screencircle.data.** {
  *;
}

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}

# Keep data classes
-keepclassmembers class com.example.screencircle.data.repository.GroupMember {
    <init>();
    <fields>;
}

# Keep Room entities
-keep class com.example.screencircle.data.local.DailyUsage { *; }
