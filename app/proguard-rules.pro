# Ledger release rules (R8). Used together with proguard-android-optimize.txt and the rules
# each library ships (Room, OkHttp, Material, AndroidX all bring their own).
#
# Nothing in the app is looked up by name at runtime (no reflection, Gson or Serializable),
# and every Activity/Service in AndroidManifest.xml is kept automatically, so no -keep rules
# are needed for Ledger's own classes. If a release build crashes with ClassNotFoundException
# or NoSuchMethodException, add a -keep rule for that class here.

# Readable crash reports: keep line numbers, but hide the original source file names.
# Decode stack traces with the release's build/outputs/mapping/release/mapping.txt
# (Android Studio: Build > Analyze Stack Trace, or the retrace tool).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip debug/info logging from release builds (e.g. the autofill service's scan messages),
# so nothing about what Ledger is doing ends up in logcat. Warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static boolean isLoggable(java.lang.String, int);
}
