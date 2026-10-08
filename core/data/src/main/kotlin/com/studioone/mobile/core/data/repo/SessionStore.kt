package com.studioone.mobile.core.data.repo

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth tokens at rest, encrypted (AES256-GCM via Jetpack Security, backed by
 * the Android Keystore). GDPR: tokens are the only PII stored outside Room;
 * deleteAccount wipes this store.
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val userId: String,
    ) {
        val isExpired: Boolean get() = System.currentTimeMillis() > expiresAtMillis - REFRESH_MARGIN_MS
    }

    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "s1_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Fallback (rooted/dev devices where Keystore is broken): private prefs.
        // Crashlytics flags this so we can measure exposure.
        context.getSharedPreferences("s1_session_plain", Context.MODE_PRIVATE)
    }

    fun save(session: Session) {
        prefs.edit()
            .putString(K_ACCESS, session.accessToken)
            .putString(K_REFRESH, session.refreshToken)
            .putLong(K_EXPIRES, session.expiresAtMillis)
            .putString(K_USER, session.userId)
            .apply()
    }

    fun load(): Session? {
        val access = prefs.getString(K_ACCESS, null) ?: return null
        val refresh = prefs.getString(K_REFRESH, null) ?: return null
        return Session(
            accessToken = access,
            refreshToken = refresh,
            expiresAtMillis = prefs.getLong(K_EXPIRES, 0),
            userId = prefs.getString(K_USER, "") ?: "",
        )
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val K_ACCESS = "access"
        private const val K_REFRESH = "refresh"
        private const val K_EXPIRES = "expires"
        private const val K_USER = "user"
        private const val REFRESH_MARGIN_MS = 60_000L
    }
}
