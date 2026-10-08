package com.studioone.mobile.core.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Subscription tiers. Entitlement checks funnel through
 * core:domain UseEntitlement so free-tier limits (track count, export quality,
 * cloud storage) are enforced in ONE place — never sprinkled through features.
 */
@Serializable
enum class SubscriptionTier(val displayName: String) {
    FREE("StudioOne Free"),
    PRO("StudioOne Pro"),
    PRO_PLUS("StudioOne Pro+");

    val isPaid: Boolean get() = this != FREE
}

/** Concrete limits per tier (remote-configurable — see backend/edge-functions/get-entitlements). */
@Serializable
data class TierLimits(
    val tier: SubscriptionTier,
    val maxProjects: Int = 3,                 // Int.MAX = unlimited
    val maxTracksPerProject: Int = 8,
    val maxSimultaneousRecordTracks: Int = 1,
    val cloudStorageMb: Long = 200,
    val maxExportBitrateKbps: Int = 192,
    val losslessExport: Boolean = false,
    val stemExport: Boolean = false,
    val premiumInstruments: Boolean = false,
    val premiumFx: Boolean = false,
    val adsEnabled: Boolean = true,
    val realtimeCollaborators: Int = 1,       // +owner
    val versionHistoryDays: Int = 7,
    val distributionEnabled: Boolean = false,
) {
    companion object {
        val FREE = TierLimits(SubscriptionTier.FREE)
        val PRO = TierLimits(
            tier = SubscriptionTier.PRO, maxProjects = Int.MAX_VALUE, maxTracksPerProject = 64,
            maxSimultaneousRecordTracks = 4, cloudStorageMb = 20_000, maxExportBitrateKbps = 320,
            losslessExport = true, stemExport = true, premiumInstruments = true, premiumFx = true,
            adsEnabled = false, realtimeCollaborators = 8, versionHistoryDays = 90,
        )
        val PRO_PLUS = PRO.copy(
            tier = SubscriptionTier.PRO_PLUS, maxTracksPerProject = 256,
            maxSimultaneousRecordTracks = 8, cloudStorageMb = 200_000,
            realtimeCollaborators = 32, versionHistoryDays = 365, distributionEnabled = true,
        )
    }
}

@Serializable
data class Entitlement(
    val userId: UserId,
    val tier: SubscriptionTier,
    val limits: TierLimits,
    val expiresAt: Instant? = null,        // null = lifetime
    val inGracePeriod: Boolean = false,
    val ownedPackIds: List<String> = emptyList(), // purchased à-la-carte IAPs
    val storageUsedBytes: Long = 0,
)

/** A purchasable product (subscription or one-shot pack). */
@Serializable
data class Product(
    val productId: String,
    val kind: ProductKind,
    val title: String,
    val description: String,
    val priceMicros: Long,
    val currencyCode: String,
    val formattedPrice: String? = null,    // from Play Billing when available
    val subscriptionPeriodDays: Int? = null,
    val freeTrialDays: Int = 0,
    val grantsPackId: String? = null,      // for sound-pack IAPs
)

@Serializable
enum class ProductKind { SUB_MONTHLY, SUB_ANNUAL, SOUND_PACK, PRESET_PACK, ARTIST_SERVICE, TIP }

enum class PurchaseState { PENDING, PURCHASED, FAILED, CANCELLED, REFUNDED }

/** Non-intrusive ad placements for the free tier (never inside recording/editing flows). */
enum class AdPlacement(val displayName: String) {
    FEED_NATIVE("Feed native card"),
    PROJECT_LIST_BANNER("Projects list banner"),
    EXPORT_COMPLETE_INTERSTITIAL("Post-export interstitial"),
    LOOP_BROWSER_REWARDED("Rewarded unlock (temporary pack access)");

    /** Rule: ads must never interrupt creation. Enforced by placement gating. */
    val allowedDuringSession: Boolean
        get() = this != EXPORT_COMPLETE_INTERSTITIAL
}

/** Artist monetization event (tips, paid downloads). */
@Serializable
data class TipEvent(
    val id: String,
    val fromUserId: UserId,
    val toUserId: UserId,
    val amountMicros: Long,
    val currencyCode: String,
    val postId: PostId? = null,
    val message: String? = null,
    val createdAt: Instant,
)
