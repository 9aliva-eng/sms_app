# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Retrofit interfaces
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# Keep Gson models
-keep class com.smsapp.** { *; }
-keepclassmembers class com.smsapp.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
