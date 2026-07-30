# R8 / ProGuard rules for release builds.
#
# Most of this app is reached from the manifest (activities, the notification
# listener, the Application class), which AGP keeps automatically. The rules below
# cover the few things static analysis can't see.

# Views inflated from XML are constructed reflectively by LayoutInflater.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# The notification listener is bound by the system by name; keep it intact so
# enabling "Notification access" keeps working in release builds.
-keep class com.radiolauncher.MediaNotificationListener { *; }

# Keep the app entry points referenced from AndroidManifest.xml.
-keep class com.radiolauncher.App
-keep class com.radiolauncher.** extends android.app.Activity

# org.json is part of the platform.
-dontwarn org.json.**

# Line numbers make release crash reports readable; hide the original file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
