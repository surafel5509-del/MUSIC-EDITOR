package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Post(
    val id: PostId,
    val authorId: UserId,
    val author: UserProfile?,            // denormalized for feed rendering
    val kind: PostKind,
    val text: String?,
    val mediaUrl: String? = null,        // rendered audio/video preview
    val waveformUrl: String? = null,
    val durationFrames: Long = 0,
    val projectId: ProjectId? = null,    // when the post publishes a track
    val isRemixOf: PostId? = null,
    val challengeId: String? = null,
    val genre: String? = null,
    val bpm: Double? = null,
    val key: MusicalKey? = null,
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val repostCount: Int = 0,
    val playCount: Int = 0,
    val likedByMe: Boolean = false,
    val createdAt: Instant,
    val visibility: PostVisibility = PostVisibility.PUBLIC,
    val isPremiumOnly: Boolean = false,  // artist monetization: paid content
    val priceMicros: Long = 0,
)

@Serializable
enum class PostKind { TRACK, REMIX, WIP /* work in progress */, PODCAST_EPISODE, TEXT, CHALLENGE_ENTRY, LIVE_STREAM }
@Serializable
enum class PostVisibility { PUBLIC, FOLLOWERS, LINK_ONLY, PRIVATE }

@Serializable
data class FeedFilter(
    val tab: FeedTab = FeedTab.FOR_YOU,
    val genre: String? = null,
    val mood: LibraryMood? = null,
)

@Serializable
enum class FeedTab(val displayName: String) {
    FOR_YOU("For You"), FOLLOWING("Following"), TRENDING("Trending"),
    NEW("New"), CHALLENGES("Challenges"), PODCASTS("Podcasts");
}

@Serializable
data class FeedComment(
    val id: String,
    val postId: PostId,
    val authorId: UserId,
    val author: UserProfile?,
    val text: String,
    val likeCount: Int = 0,
    val createdAt: Instant,
    val parentId: String? = null,        // threaded replies
)

@Serializable
data class Challenge(
    val id: String,
    val title: String,
    val description: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val entryCount: Int,
    val prizes: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
    val coverUrl: String? = null,
    val requiredStem: LibraryItem? = null, // e.g. remix this loop
)

@Serializable
data class DirectMessage(
    val id: String,
    val conversationId: String,
    val senderId: UserId,
    val text: String?,
    val attachmentUrl: String? = null,   // shared track/stem
    val readAt: Instant? = null,
    val createdAt: Instant,
)

@Serializable
data class Conversation(
    val id: String,
    val participants: List<UserId>,
    val lastMessage: DirectMessage?,
    val unreadCount: Int = 0,
    val updatedAt: Instant?,
)
