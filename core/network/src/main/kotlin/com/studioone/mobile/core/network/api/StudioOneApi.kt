package com.studioone.mobile.core.network.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

// ── Edge function contracts (backend/supabase/functions) ────────────────────

@Serializable
data class UpsertProjectRequest(
    val projectId: String,
    val documentVersion: Long,
    val document: String,            // serialized project JSON
    val baseVersion: Long,           // last known server version (optimistic concurrency)
    val lamport: Long,
)

@Serializable
data class UpsertProjectResponse(
    val accepted: Boolean,
    val serverVersion: Long,
    /** When accepted=false: the ops the client missed; merge then retry. */
    val missingOps: List<OpWire> = emptyList(),
)

@Serializable
data class OpWire(
    val opId: String,
    val projectId: String,
    val authorId: String,
    val lamport: Long,
    val wallClock: String,           // ISO-8601
    val payload: String,             // serialized CollabOp JSON
)

@Serializable
data class PushOpsRequest(val ops: List<OpWire>)

@Serializable
data class PushOpsResponse(val accepted: Int, val rejected: List<String> = emptyList())

@Serializable
data class PullOpsResponse(val ops: List<OpWire>, val serverLamport: Long)

@Serializable
data class ShareLinkRequest(
    val projectId: String,
    val role: String,
    val expiresInHours: Int? = null,
    val forkOnAccept: Boolean = false,
)

@Serializable
data class ShareLinkResponse(val token: String, val url: String, val expiresAt: String?)

@Serializable
data class AcceptShareRequest(val token: String)

@Serializable
data class AcceptShareResponse(
    val projectId: String,
    val role: String,
    val forked: Boolean,
    val documentVersion: Long,
    val document: String?,           // present when forked or first join
)

@Serializable
data class EntitlementsResponse(
    val tier: String,
    val limitsJson: String,
    val expiresAt: String?,
    val ownedPacks: List<String> = emptyList(),
    val storageUsedBytes: Long = 0,
)

@Serializable
data class VerifyPurchaseRequest(
    val productId: String,
    val purchaseToken: String,
    val packageName: String,
)

@Serializable
data class VerifyPurchaseResponse(val granted: Boolean, val tier: String?, val expiresAt: String?)

@Serializable
data class PublishPostRequest(
    val kind: String,
    val text: String?,
    val mediaStoragePath: String?,
    val waveformPath: String?,
    val projectId: String?,
    val genre: String?,
    val bpm: Double?,
    val keyName: String?,
    val visibility: String,
    val isPremiumOnly: Boolean = false,
    val priceMicros: Long = 0,
    val remixOf: String? = null,
    val challengeId: String? = null,
)

@Serializable
data class FeedResponse(val posts: List<PostWire>, val nextCursor: String?)

@Serializable
data class PostWire(
    val id: String,
    val authorId: String,
    val authorName: String,
    val authorHandle: String?,
    val authorAvatar: String?,
    val kind: String,
    val text: String?,
    val mediaUrl: String?,
    val waveformUrl: String?,
    val likeCount: Int,
    val commentCount: Int,
    val playCount: Int,
    val likedByMe: Boolean,
    val createdAt: String,
    val bpm: Double?,
    val keyName: String?,
    val genre: String?,
    val projectId: String?,
    val isPremiumOnly: Boolean,
    val priceMicros: Long,
)

@Serializable
data class LibrarySearchResponse(val items: List<LibraryItemWire>, val nextCursor: String?)

@Serializable
data class LibraryItemWire(
    val id: String,
    val name: String,
    val category: String,
    val genre: String?,
    val mood: String?,
    val bpm: Double?,
    val keyName: String?,
    val isLoop: Boolean,
    val durationFrames: Long,
    val sampleRate: Int,
    val format: String,
    val previewUrl: String,
    val fullUrl: String,
    val artworkUrl: String?,
    val packId: String?,
    val isPremium: Boolean,
    val tags: List<String>,
    val license: String,
)

@Serializable
data class ExportJobRequest(
    val projectId: String,
    val kind: String,
    val format: String,
    val sampleRate: Int,
    val bitDepth: String,
    val bitrateKbps: Int,
    val loudnessTargetLUFS: Float?,
    val metadataJson: String,
)

@Serializable
data class ExportJobResponse(val jobId: String, val statusUrl: String)

@Serializable
data class AnalyzeAudioRequest(val storagePath: String)

@Serializable
data class AnalyzeAudioResponse(val bpm: Double?, val key: String?, val loudnessLUFS: Float?)

/**
 * HTTP surface of the StudioOne backend. REST for CRUD-ish paths, edge
 * functions for anything transactional (op ingest, purchase verification,
 * share acceptance, audio analysis).
 */
interface StudioOneApi {

    // ── Collaboration ops ───────────────────────────────────────────────────
    @POST("collab-push-ops")
    suspend fun pushOps(@Body request: PushOpsRequest): Response<PushOpsResponse>

    @GET("collab-pull-ops")
    suspend fun pullOps(
        @Query("project_id") projectId: String,
        @Query("after_lamport") afterLamport: Long,
        @Query("limit") limit: Int = 500,
    ): Response<PullOpsResponse>

    @POST("project-upsert")
    suspend fun upsertProject(@Body request: UpsertProjectRequest): Response<UpsertProjectResponse>

    @GET("project-get")
    suspend fun getProject(@Query("project_id") projectId: String): Response<UpsertProjectResponse>

    // ── Sharing ─────────────────────────────────────────────────────────────
    @POST("share-create")
    suspend fun createShareLink(@Body request: ShareLinkRequest): Response<ShareLinkResponse>

    @POST("share-accept")
    suspend fun acceptShareLink(@Body request: AcceptShareRequest): Response<AcceptShareResponse>

    // ── Entitlements & purchases ────────────────────────────────────────────
    @GET("get-entitlements")
    suspend fun getEntitlements(): Response<EntitlementsResponse>

    @POST("verify-purchase")
    suspend fun verifyPurchase(@Body request: VerifyPurchaseRequest): Response<VerifyPurchaseResponse>

    // ── Social ──────────────────────────────────────────────────────────────
    @GET("feed")
    suspend fun feed(
        @Query("tab") tab: String,
        @Query("cursor") cursor: String? = null,
        @Query("genre") genre: String? = null,
        @Query("limit") limit: Int = 25,
    ): Response<FeedResponse>

    @POST("publish-post")
    suspend fun publishPost(@Body request: PublishPostRequest): Response<PostWire>

    @POST("posts/{postId}/like")
    suspend fun likePost(@Path("postId") postId: String): Response<Unit>

    @DELETE("posts/{postId}/like")
    suspend fun unlikePost(@Path("postId") postId: String): Response<Unit>

    // ── Library ─────────────────────────────────────────────────────────────
    @GET("library-search")
    suspend fun searchLibrary(
        @Query("q") query: String?,
        @Query("category") category: String?,
        @Query("genre") genre: String?,
        @Query("bpm_min") bpmMin: Double?,
        @Query("bpm_max") bpmMax: Double?,
        @Query("key") key: String?,
        @Query("premium_ok") premiumOk: Boolean?,
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 40,
    ): Response<LibrarySearchResponse>

    // ── Server-side export & analysis ───────────────────────────────────────
    @POST("export-render")
    suspend fun createExportJob(@Body request: ExportJobRequest): Response<ExportJobResponse>

    @GET
    suspend fun getRaw(@Url url: String): Response<okhttp3.ResponseBody>

    // ── Storage (Supabase Storage REST; bucket paths) ───────────────────────
    @POST
    suspend fun storageUpload(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Header("x-upsert") upsert: String = "true",
        @Body body: okhttp3.RequestBody,
    ): Response<kotlinx.serialization.json.JsonObject>

    @GET
    suspend fun storageSignedUrl(@Url url: String): Response<kotlinx.serialization.json.JsonObject>
}
