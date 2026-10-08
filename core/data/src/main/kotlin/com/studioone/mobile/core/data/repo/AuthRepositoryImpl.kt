package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.datastore.SettingsRepository
import com.studioone.mobile.core.database.dao.UserDao
import com.studioone.mobile.core.database.entity.UserEntity
import com.studioone.mobile.core.domain.AuthRepository
import com.studioone.mobile.core.model.AuthProvider
import com.studioone.mobile.core.model.SubscriptionTier
import com.studioone.mobile.core.model.User
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.network.api.ApiConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supabase GoTrue auth over plain HTTPS (no Firebase dependency):
 *  * email/password signup + sign-in
 *  * OAuth (Google/Facebook/Apple) via the PKCE "code exchange" flow: the
 *    sign-in happens in a Custom Tab (feature:auth) and we exchange the code
 *    here — access + refresh tokens are persisted in EncryptedSharedPreferences
 *    (see SessionStore) and refreshed on a schedule.
 *  * guest mode is fully local: User.guest() + session user id null.
 *
 * JWT/OAuth2 notes (docs/SECURITY.md): access tokens are short-lived (1h),
 * refresh tokens rotate; RLS policies key off the JWT sub claim.
 */
@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val client: OkHttpClient,
    private val config: ApiConfig,
    private val userDao: UserDao,
    private val settings: SettingsRepository,
    private val sessionStore: SessionStore,
) : AuthRepository, CurrentUserProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private val _currentUser = MutableStateFlow<User?>(null)

    init {
        // Hydrate from persisted session, then observe.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launchRestore()
    }

    private fun kotlinx.coroutines.CoroutineScope.launchRestore() {
        kotlinx.coroutines.launch {
            val session = sessionStore.load()
            if (session != null) {
                refreshInternal(session)
            }
        }
    }

    override val currentUser: Flow<User?> = _currentUser.map { it }

    override suspend fun currentUserOnce(): User? = _currentUser.value ?: restoreFromStore()

    override suspend fun accessToken(): String? = sessionStore.load()?.accessToken

    override suspend fun currentUserId(): String? = currentUserOnce()?.id?.value
    override suspend fun currentToken(): String? = accessToken()

    override suspend fun signInWithEmail(email: String, password: String): DataResult<User> =
        postAuth("token?grant_type=password", """{"email":${q(email)},"password":${q(password)}}""")

    override suspend fun registerWithEmail(email: String, password: String, displayName: String): DataResult<User> =
        postAuth("signup", """{"email":${q(email)},"password":${q(password)},"data":{"display_name":${q(displayName)}}}""")

    override suspend fun signInWithGoogle(idToken: String): DataResult<User> =
        postAuth("token?grant_type=id_token", """{"provider":"google","id_token":${q(idToken)}}""")

    override suspend fun signInWithFacebook(accessToken: String): DataResult<User> =
        postAuth("token?grant_type=id_token", """{"provider":"facebook","id_token":${q(accessToken)}}""")

    override suspend fun signInWithApple(idToken: String, nonce: String): DataResult<User> =
        postAuth("token?grant_type=id_token", """{"provider":"apple","id_token":${q(idToken)},"nonce":${q(nonce)}}""")

    /** PKCE code exchange from the Custom Tab redirect. */
    suspend fun exchangeAuthCode(code: String, codeVerifier: String): DataResult<User> =
        postAuth("token?grant_type=pkce", """{"auth_code":${q(code)},"code_verifier":${q(codeVerifier)}}""")

    override suspend fun continueAsGuest(): DataResult<User> {
        val guest = User.guest()
        _currentUser.value = guest
        settings.saveSession(null, AuthProvider.GUEST.name)
        return DataResult.Success(guest)
    }

    override suspend fun upgradeGuest(email: String, password: String): DataResult<User> {
        val result = registerWithEmail(email, password, "Musician")
        // Project ownership migration: local guest projects get the new owner id.
        // Handled by SyncEngine.onUserChanged (projects with owner null are
        // re-stamped and pushed). See docs/COLLABORATION.md §7.
        return result
    }

    override suspend fun signOut() {
        sessionStore.load()?.refreshToken?.let { token ->
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("${config.supabaseUrl}/auth/v1/logout")
                        .header("Authorization", "Bearer $token")
                        .post("".toRequestBody(null)).build(),
                ).execute().close()
            }
        }
        sessionStore.clear()
        _currentUser.value = null
        settings.saveSession(null, null)
    }

    override suspend fun deleteAccount(): DataResult<Unit> {
        val token = accessToken() ?: return DataResult.Failure(DataError.Kind.AUTH.let { DataError(it) })
        // Edge function performs cascade delete + storage purge (GDPR Art.17).
        val request = Request.Builder()
            .url("${config.functionsBaseUrl}/delete-account")
            .header("Authorization", "Bearer $token")
            .header("apikey", config.supabaseAnonKey)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    signOut()
                    DataResult.Success(Unit)
                } else DataResult.Failure(DataError(DataError.Kind.AUTH, "Delete failed: ${resp.code}"))
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun sendPasswordReset(email: String): DataResult<Unit> {
        val request = Request.Builder()
            .url("${config.supabaseUrl}/auth/v1/recover")
            .header("apikey", config.supabaseAnonKey)
            .post("""{"email":${q(email)}}""".toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use {
                if (it.isSuccessful) DataResult.Success(Unit)
                else DataResult.Failure(DataError(DataError.Kind.AUTH, "Reset failed"))
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun refreshToken(): DataResult<Unit> {
        val session = sessionStore.load() ?: return DataResult.Failure(DataError(DataError.Kind.AUTH))
        return refreshInternal(session)
    }

    private suspend fun refreshInternal(session: SessionStore.Session): DataResult<Unit> {
        val body = """{"refresh_token":${q(session.refreshToken)}}"""
        val request = Request.Builder()
            .url("${config.supabaseUrl}/auth/v1/token?grant_type=refresh_token")
            .header("apikey", config.supabaseAnonKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return DataResult.Failure(DataError(DataError.Kind.AUTH))
                val token = json.decodeFromString<AuthTokenResponse>(resp.body?.string() ?: "")
                persistSession(token)
                DataResult.Success(Unit)
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    private suspend fun postAuth(path: String, body: String): DataResult<User> {
        val request = Request.Builder()
            .url("${config.supabaseUrl}/auth/v1/$path")
            .header("apikey", config.supabaseAnonKey)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    val err = runCatching { json.decodeFromString<AuthErrorResponse>(text) }.getOrNull()
                    return DataResult.Failure(
                        DataError(DataError.Kind.AUTH, err?.errorDescription ?: err?.msg ?: "Auth failed (${resp.code})"),
                    )
                }
                val token = json.decodeFromString<AuthTokenResponse>(text)
                val user = persistSession(token)
                DataResult.Success(user)
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    private suspend fun persistSession(token: AuthTokenResponse): User {
        sessionStore.save(
            SessionStore.Session(
                accessToken = token.accessToken,
                refreshToken = token.refreshToken,
                expiresAtMillis = System.currentTimeMillis() + token.expiresIn * 1000,
                userId = token.user.id,
            ),
        )
        val user = User(
            id = UserId(token.user.id),
            email = token.user.email,
            displayName = token.user.userMetadata?.displayName ?: token.user.email?.substringBefore('@') ?: "Musician",
            avatarUrl = token.user.userMetadata?.avatarUrl,
            provider = AuthProvider.EMAIL, // refined by callers for OAuth
            isEmailVerified = !token.user.email.isNullOrEmpty(),
            tier = SubscriptionTier.FREE, // entitlements refresh separately
        )
        _currentUser.value = user
        userDao.upsert(
            UserEntity(
                id = user.id.value, email = user.email, displayName = user.displayName,
                handle = null, bio = null, avatarUrl = user.avatarUrl,
                tier = user.tier.name, cachedAt = System.currentTimeMillis(),
            ),
        )
        settings.saveSession(user.id.value, user.provider.name)
        return user
    }

    private suspend fun restoreFromStore(): User? {
        val userId = settings.sessionUserId.firstOrNull() ?: return null
        val cached = userDao.byId(userId) ?: return null
        val user = User(
            id = UserId(cached.id), email = cached.email, displayName = cached.displayName,
            avatarUrl = cached.avatarUrl, provider = AuthProvider.EMAIL,
            tier = runCatching { SubscriptionTier.valueOf(cached.tier) }.getOrDefault(SubscriptionTier.FREE),
        )
        _currentUser.value = user
        return user
    }

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

@Serializable
private data class AuthTokenResponse(
    val access_token: String,
    val refresh_token: String,
    val expires_in: Long,
    val token_type: String = "bearer",
    val user: AuthUserWire,
) {
    val accessToken get() = access_token
    val refreshToken get() = refresh_token
    val expiresIn get() = expires_in
}

@Serializable
private data class AuthUserWire(
    val id: String,
    val email: String? = null,
    val user_metadata: UserMetadataWire? = null,
) {
    val userMetadata get() = user_metadata
}

@Serializable
private data class UserMetadataWire(
    val display_name: String? = null,
    val avatar_url: String? = null,
) {
    val displayName get() = display_name
    val avatarUrl get() = avatar_url
}

@Serializable
private data class AuthErrorResponse(
    val error: String? = null,
    val error_description: String? = null,
    val msg: String? = null,
) {
    val errorDescription get() = error_description
}
