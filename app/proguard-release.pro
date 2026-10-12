# The module is loaded by Xposed, and host-side UI creates module objects reflectively.
# Keep module contracts intact while shrinking and optimizing dependencies.
-keep class com.douai.module.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# These names also identify AndroidX classes in the host ClassLoader.
-keep class androidx.activity.result.** { *; }
-keep interface androidx.activity.result.** { *; }
-keepclassmembers class androidx.activity.ComponentActivity {
    public androidx.activity.result.ActivityResultRegistry getActivityResultRegistry();
}

# Provided by the Xposed runtime rather than packaged in the APK.
-dontwarn io.github.libxposed.api.**
