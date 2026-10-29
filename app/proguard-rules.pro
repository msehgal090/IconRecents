# Add project specific ProGuard rules here.
# Keep Xposed hook entry point
-keep class com.manu.iconrecents.XposedHook { *; }
-keep class de.robv.android.xposed.** { *; }
-keepattributes *Annotation*
