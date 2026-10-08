# ── Native engine ────────────────────────────────────────────────────────────
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.studioone.mobile.core.audio.NativeAudioEngine { *; }

# ── kotlinx.serialization (model wire contracts) ─────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.studioone.mobile.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.studioone.mobile.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Oboe / AAudio symbols are dlopen'd by the system; keep our JNI class. ───
-keep class com.google.oboe.** { *; }

# ── Billing ──────────────────────────────────────────────────────────────────
-keep class com.android.billingclient.api.** { *; }

# ── Firebase Auth models ─────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
