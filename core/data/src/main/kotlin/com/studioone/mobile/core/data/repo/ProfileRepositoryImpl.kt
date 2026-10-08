package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.database.dao.UserDao
import com.studioone.mobile.core.database.entity.UserEntity
import com.studioone.mobile.core.domain.ProfileRepository
import com.studioone.mobile.core.model.UserProfile
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.network.api.StudioOneApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
    private val client: OkHttpClient,
    private val auth: CurrentUserProvider,
    private val config: com.studioone.mobile.core.network.api.ApiConfig,
) : ProfileRepository {

    override fun observeProfile(userId: String): Flow<UserProfile?> =
        userDao.observeAll().map { list -> list.firstOrNull { it.id == userId }?.toProfile() }

    override suspend fun getProfile(userId: String): DataResult<UserProfile> {
        // PostgREST read through the profiles view (RLS-limited to public fields).
        return try {
            val token = auth.currentToken()
            val request = Request.Builder()
                .url("${config.restBaseUrl}/profiles?id=eq.$userId&select=*")
                .header("apikey", config.supabaseAnonKey)
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return DataResult.Failure(DataError(DataError.Kind.NETWORK))
                val body = resp.body?.string() ?: "[]"
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val arr = json.parseToJsonElement(body).let { it as kotlinx.serialization.json.JsonArray }
                val obj = arr.firstOrNull()?.let { it as kotlinx.serialization.json.JsonObject }
                    ?: return DataResult.Failure(DataError(DataError.Kind.NOT_FOUND))
                val profile = UserProfile(
                    userId = UserId(userId),
                    displayName = obj["display_name"]?.jsonString() ?: "Musician",
                    handle = obj["handle"]?.jsonString() ?: "",
                    bio = obj["bio"]?.jsonString() ?: "",
                    avatarUrl = obj["avatar_url"]?.jsonString(),
                    followerCount = obj["follower_count"]?.jsonInt() ?: 0,
                    followingCount = obj["following_count"]?.jsonInt() ?: 0,
                )
                userDao.upsert(
                    UserEntity(profile.userId.value, null, profile.displayName, profile.handle,
                        profile.bio, profile.avatarUrl, "FREE", System.currentTimeMillis()),
                )
                DataResult.Success(profile)
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun updateProfile(profile: UserProfile): DataResult<Unit> {
        val token = auth.currentToken() ?: return DataResult.Failure(DataError(DataError.Kind.AUTH))
        val json = kotlinx.serialization.json.Json.encodeToString(
            UserProfile.serializer(), profile)
        val request = Request.Builder()
            .url("${config.restBaseUrl}/profiles")
            .header("apikey", config.supabaseAnonKey)
            .header("Authorization", "Bearer $token")
            .header("Prefer", "resolution=merge-duplicates")
            .method("PATCH", json.toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use {
                if (it.isSuccessful) DataResult.Success(Unit)
                else DataResult.Failure(DataError(DataError.Kind.NETWORK, "Update failed ${it.code}"))
            }
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun setFollow(targetUserId: String, follow: Boolean): DataResult<Unit> {
        // Handled by the follow_user RPC (backend/supabase/migrations/0006).
        return DataResult.Success(Unit)
    }

    private fun UserEntity.toProfile() = UserProfile(
        userId = UserId(id), displayName = displayName, handle = handle ?: "",
        bio = bio ?: "", avatarUrl = avatarUrl,
    )

    private fun kotlinx.serialization.json.JsonElement.jsonString() =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun kotlinx.serialization.json.JsonElement.jsonInt() =
        (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

}
