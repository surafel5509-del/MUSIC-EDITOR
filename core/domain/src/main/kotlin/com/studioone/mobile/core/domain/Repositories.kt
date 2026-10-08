package com.studioone.mobile.core.domain

import com.studioone.mobile.core.model.AudioDeviceInfo
import com.studioone.mobile.core.model.AudioFileRef
import com.studioone.mobile.core.model.AuthProvider
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.Entitlement
import com.studioone.mobile.core.model.ExportProgress
import com.studioone.mobile.core.model.ExportSettings
import com.studioone.mobile.core.model.FeedComment
import com.studioone.mobile.core.model.FeedFilter
import com.studioone.mobile.core.model.LatencyReport
import com.studioone.mobile.core.model.LibraryItem
import com.studioone.mobile.core.model.MergeOutcome
import com.studioone.mobile.core.model.Post
import com.studioone.mobile.core.model.Product
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectRole
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.PurchaseState
import com.studioone.mobile.core.model.SamplePack
import com.studioone.mobile.core.model.ShareLink
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.User
import com.studioone.mobile.core.model.UserProfile
import com.studioone.mobile.core.model.VersionSnapshot
import kotlinx.coroutines.flow.Flow

// Repository contracts live in domain; implementations in core:data (Clean
// Architecture dependency rule: domain knows nothing about Room/Retrofit/Firebase).

interface AuthRepository {
    val currentUser: Flow<User?>
    suspend fun currentUserOnce(): User?
    suspend fun signInWithEmail(email: String, password: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun registerWithEmail(email: String, password: String, displayName: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun signInWithGoogle(idToken: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun signInWithFacebook(accessToken: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun signInWithApple(idToken: String, nonce: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun continueAsGuest(): com.studioone.mobile.core.common.DataResult<User>
    /** Convert a guest session to a real account, migrating local projects. */
    suspend fun upgradeGuest(email: String, password: String): com.studioone.mobile.core.common.DataResult<User>
    suspend fun signOut()
    suspend fun deleteAccount(): com.studioone.mobile.core.common.DataResult<Unit> // GDPR Art.17
    suspend fun sendPasswordReset(email: String): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun accessToken(): String?
    suspend fun refreshToken(): com.studioone.mobile.core.common.DataResult<Unit>
}

interface ProfileRepository {
    fun observeProfile(userId: String): Flow<UserProfile?>
    suspend fun getProfile(userId: String): com.studioone.mobile.core.common.DataResult<UserProfile>
    suspend fun updateProfile(profile: UserProfile): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun setFollow(targetUserId: String, follow: Boolean): com.studioone.mobile.core.common.DataResult<Unit>
}

interface ProjectRepository {
    fun observeProjects(includeArchived: Boolean = false): Flow<List<Project>>
    fun observeProject(projectId: ProjectId): Flow<Project?>
    suspend fun getProject(projectId: ProjectId): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun createProject(
        name: String, template: ProjectTemplate, bpm: Double = 120.0,
    ): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun saveProject(project: Project, snapshotLabel: String? = null): com.studioone.mobile.core.common.DataResult<Long>
    suspend fun duplicateProject(projectId: ProjectId, newName: String): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun archiveProject(projectId: ProjectId, archived: Boolean): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun deleteProject(projectId: ProjectId): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun forkProject(projectId: ProjectId): com.studioone.mobile.core.common.DataResult<Project> // remix
    fun observeVersions(projectId: ProjectId): Flow<List<VersionSnapshot>>
    suspend fun restoreVersion(versionId: String): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun mergeRemote(projectId: ProjectId): com.studioone.mobile.core.common.DataResult<MergeOutcome>
    suspend fun lastOpenedProjectId(): ProjectId?
    suspend fun markOpened(projectId: ProjectId)
}

interface TrackRepository {
    suspend fun addTrack(projectId: ProjectId, track: Track): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun removeTrack(projectId: ProjectId, trackId: TrackId): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun updateTrack(projectId: ProjectId, trackId: TrackId, transform: (Track) -> Track): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun reorderTracks(projectId: ProjectId, orderedIds: List<TrackId>): com.studioone.mobile.core.common.DataResult<Project>
    /** Freeze: offline-render the track (incl. FX/instrument) to a bounce file. */
    suspend fun freezeTrack(projectId: ProjectId, trackId: TrackId): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun unfreezeTrack(projectId: ProjectId, trackId: TrackId): com.studioone.mobile.core.common.DataResult<Project>
}

interface SampleRepository {
    suspend fun importFromUri(uri: String, projectId: ProjectId?): com.studioone.mobile.core.common.DataResult<AudioFileRef>
    suspend fun importFromMediaStore(mediaId: Long): com.studioone.mobile.core.common.DataResult<AudioFileRef>
    suspend fun extractPeaks(fileRef: AudioFileRef): com.studioone.mobile.core.common.DataResult<AudioFileRef>
    suspend fun detectBpmAndKey(fileRef: AudioFileRef): com.studioone.mobile.core.common.DataResult<Pair<Double?, String?>>
    suspend fun deleteSample(sampleId: String): com.studioone.mobile.core.common.DataResult<Unit>
    fun observeProjectSamples(projectId: ProjectId): Flow<List<AudioFileRef>>
    suspend fun listInputDevices(): List<AudioDeviceInfo>
}

interface LibraryRepository {
    suspend fun search(
        query: String?, category: String?, genre: String?, bpmRange: ClosedFloatingPointRange<Double>?,
        key: String?, cursor: String? = null,
    ): com.studioone.mobile.core.common.DataResult<Pair<List<LibraryItem>, String?>>
    fun observePacks(): Flow<List<SamplePack>>
    suspend fun downloadItem(itemId: String): Flow<DownloadProgress>
    suspend fun purchasePack(productId: String): com.studioone.mobile.core.common.DataResult<PurchaseState>
    fun observeDownloaded(): Flow<List<LibraryItem>>
    suspend fun deleteDownload(itemId: String): com.studioone.mobile.core.common.DataResult<Unit>
}

data class DownloadProgress(val itemId: String, val fraction: Float, val bytesDone: Long, val bytesTotal: Long, val done: Boolean = false, val error: String? = null)

interface CollaborationRepository {
    /** Open (or rejoin) the live session for a project. */
    suspend fun openSession(projectId: ProjectId, baseDocument: Project): com.studioone.mobile.core.common.DataResult<CollabSessionHandle>
    fun observeComments(projectId: ProjectId): Flow<List<Comment>>
    suspend fun addComment(comment: Comment): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun resolveComment(commentId: String, resolved: Boolean): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun createShareLink(projectId: ProjectId, role: ProjectRole, forkOnAccept: Boolean, expiresHours: Int?): com.studioone.mobile.core.common.DataResult<ShareLink>
    suspend fun acceptShareLink(token: String): com.studioone.mobile.core.common.DataResult<Project>
    suspend fun collaborators(projectId: ProjectId): com.studioone.mobile.core.common.DataResult<List<com.studioone.mobile.core.model.Collaborator>>
    suspend fun setCollaboratorRole(projectId: ProjectId, userId: String, role: ProjectRole): com.studioone.mobile.core.common.DataResult<Unit>
}

/** Opaque handle to a live CollaborationEngine (created in core:data). */
interface CollabSessionHandle {
    val projectId: ProjectId
    val document: Flow<Project>
    val events: Flow<Any>
    suspend fun submit(op: com.studioone.mobile.core.model.CollabOp)
    fun sendPresence(presence: com.studioone.mobile.core.model.CollaboratorPresence)
    fun close()
}

interface SocialRepository {
    fun observeFeed(filter: FeedFilter): Flow<List<Post>>
    suspend fun loadMoreFeed(filter: FeedFilter, cursor: String?): com.studioone.mobile.core.common.DataResult<Pair<List<Post>, String?>>
    suspend fun publishPost(post: Post): com.studioone.mobile.core.common.DataResult<Post>
    suspend fun like(postId: String, like: Boolean): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun comments(postId: String): com.studioone.mobile.core.common.DataResult<List<FeedComment>>
    suspend fun addComment(postId: String, text: String, parentId: String?): com.studioone.mobile.core.common.DataResult<Unit>
    suspend fun deletePost(postId: String): com.studioone.mobile.core.common.DataResult<Unit>
    fun observeChallenges(): Flow<List<com.studioone.mobile.core.model.Challenge>>
}

interface ExportRepository {
    /** Local render+encode pipeline; emits progress, completes with the output uri. */
    fun exportProject(projectId: ProjectId, settings: ExportSettings): Flow<ExportProgress>
    suspend fun exportedFileUri(projectId: ProjectId): String?
    suspend fun exportStems(projectId: ProjectId, settings: ExportSettings, trackIds: List<TrackId>): Flow<ExportProgress>
    suspend fun shareTo(target: com.studioone.mobile.core.model.PublishTarget, uri: String, metadata: com.studioone.mobile.core.model.AudioMetadata): com.studioone.mobile.core.common.DataResult<Unit>
    /** Server-side render for cloud publish (used when device is constrained). */
    suspend fun createCloudExportJob(projectId: ProjectId, settings: ExportSettings): com.studioone.mobile.core.common.DataResult<String>
}

interface EntitlementRepository {
    val entitlement: Flow<Entitlement>
    suspend fun refresh(): com.studioone.mobile.core.common.DataResult<Entitlement>
    suspend fun products(): com.studioone.mobile.core.common.DataResult<List<Product>>
    suspend fun purchase(productId: String): com.studioone.mobile.core.common.DataResult<PurchaseState>
    suspend fun restorePurchases(): com.studioone.mobile.core.common.DataResult<Entitlement>
    suspend fun checkLimit(kind: LimitKind): com.studioone.mobile.core.common.DataResult<Unit>
}

enum class LimitKind(val message: String) {
    PROJECT_COUNT("Free plan is limited to 3 projects. Upgrade for unlimited."),
    TRACK_COUNT("Free plan projects support up to 8 tracks."),
    LOSSLESS_EXPORT("FLAC/WAV-master export is a Pro feature."),
    STEM_EXPORT("Stem export is a Pro feature."),
    PREMIUM_INSTRUMENT("This instrument is part of StudioOne Pro."),
    PREMIUM_FX("This effect is part of StudioOne Pro."),
    CLOUD_STORAGE("You've reached your cloud storage limit."),
    COLLABORATORS("Free plan supports 1 collaborator per project."),
    HIGH_BITRATE("320kbps export is a Pro feature."),
}

interface AudioSettingsRepository {
    val latencyReport: Flow<LatencyReport?>
    suspend fun runLatencyTest(): com.studioone.mobile.core.common.DataResult<LatencyReport>
    fun observeEngineHealth(): Flow<EngineHealth>
}

data class EngineHealth(
    val underrunsPerMinute: Float,
    val cpuLoadPercent: Float,
    val fxPoolPressure: Int,
    val thermalThrottling: Boolean,
)
