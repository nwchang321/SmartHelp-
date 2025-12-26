# Add project specific ProGuard rules here.

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep model classes
-keep class com.smarthelp.app.ApiClient$* { *; }
