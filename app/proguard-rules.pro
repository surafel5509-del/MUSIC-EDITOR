# ---- Native audio engine ----------------------------------------------------
-keepclasseswithmembers class com.studioone.audio.AudioEngine {
    native <methods>;
}

# ---- kotlinx.serialization --------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.studioone.**$$serializer { *; }
-keepclassmembers class com.studioone.** { *** Companion; }
-keepclasseswithmembers class com.studioone.** { kotlinx.serialization.KSerializer serializer(...); }

# ---- Supabase / Ktor ----------------------------------------------------------
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.ktor.**

# ---- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
