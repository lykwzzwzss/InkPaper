-keep class com.inkpaper.epd.nfc.ShizukuBridgeService {
    public <init>(...);
    *;
}
-keepclassmembers class com.inkpaper.epd.** {
    *;
}
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn android.nfc.**
-dontwarn android.os.ServiceManager
-dontwarn android.app.ActivityThread
-dontwarn android.content.pm.IPackageManager
