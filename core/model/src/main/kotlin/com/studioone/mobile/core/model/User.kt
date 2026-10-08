package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class AuthProvider { EMAIL, GOOGLE, FACEBOOK, APPLE, GUEST }

@Serializable
data class User(
    val id: UserId,
    val email: String?,
    val displayName: String,
    val avatarUrl: String? = null,
    val provider: AuthProvider,
    val isEmailVerified: Boolean = false,
    val tier: SubscriptionTier = SubscriptionTier.FREE,
    val isGuest: Boolean = false,
    val createdAt: Instant? = null,
    val gdprConsents: Map<String, Boolean> = emptyMap(), // consent key -> granted (docs/PRIVACY.md)
) {
    companion object {
        /** The synthetic local user for guest mode (fully offline capable). */
        fun guest() = User(
            id = UserId("local-guest"), email = null, displayName = "Guest",
            provider = AuthProvider.GUEST, isGuest = true,
        )
    }
}

@Serializable
data class UserProfile(
    val userId: UserId,
    val displayName: String,
    val handle: String,                  // @handle, unique, used in URLs
    val bio: String = "",
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val genres: List<String> = emptyList(),
    val instruments: List<String> = emptyList(),
    val links: List<SocialLink> = emptyList(),
    val location: String? = null,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val trackCount: Int = 0,
    val isVerifiedArtist: Boolean = false,
    val tipsEnabled: Boolean = false,
    val tipJarAddress: String? = null,   // external payment handle (Stripe/payment link)
)

@Serializable
data class SocialLink(val platform: String, val url: String)

/** Role model for project sharing & collaboration permissions. */
@Serializable
enum class ProjectRole {
    OWNER,        // full control incl. delete & permission changes
    EDITOR,       // co-edit everything except permissions
    COMMENTER,    // view + comment + annotate, no edits
    VIEWER;       // view + listen only

    val canEdit: Boolean get() = this == OWNER || this == EDITOR
    val canComment: Boolean get() = this != VIEWER
    val canManagePermissions: Boolean get() = this == OWNER
}

@Serializable
data class Collaborator(
    val userId: UserId,
    val displayName: String,
    val avatarUrl: String? = null,
    val role: ProjectRole = ProjectRole.COMMENTER,
    val joinedAt: Instant? = null,
    val isOnline: Boolean = false,
    /** Presence: what screen/position the collaborator is looking at. */
    val presence: CollaboratorPresence? = null,
)

@Serializable
data class CollaboratorPresence(
    val screen: PresenceScreen = PresenceScreen.ARRANGER,
    val cursorFrame: Long? = null,
    val selectedTrackId: TrackId? = null,
    val color: TrackColor = TrackColor.TEAL,
    val updatedAt: Instant? = null,
)

@Serializable
enum class PresenceScreen { ARRANGER, MIXER, PIANO_ROLL, LOOPS, CHAT }
