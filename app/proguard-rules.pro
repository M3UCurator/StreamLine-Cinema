# Media3 / ExoPlayer
-dontwarn org.checkerframework.**
-keep class androidx.media3.** { *; }
-keep class com.google.common.** { *; }

# Kotlinx serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.streamline.cinema.**$$serializer { *; }
-keepclassmembers class com.streamline.cinema.** { *** Companion; }
-keepclasseswithmembers class com.streamline.cinema.** { kotlinx.serialization.KSerializer serializer(...); }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
