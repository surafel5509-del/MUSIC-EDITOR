package com.studioone.mobile.core.analytics

/**
 * Consent-gated analytics & crash reporting facade.
 *
 * GDPR/CCPA rules (docs/PRIVACY.md):
 *  * NO event leaves the device until the user grants analytics consent.
 *  * Crashlytics collection is initialized ONLY with crash consent (the app
 *    module checks consent before FirebaseApp data collection flags flip).
 *  * Events never carry PII: user ids are the pseudonymous auth uid; audio
 *    content, file paths, and messages are banned fields (enforced by
 *    [AnalyticsEvent] sealed types — no free-form maps).
 */
sealed class AnalyticsEvent(val name: String) {
    // Onboarding & auth
    data object OnboardingStarted : AnalyticsEvent("onboarding_started")
    data object OnboardingCompleted : AnalyticsEvent("onboarding_completed")
    data class SignUp(val method: String) : AnalyticsEvent("sign_up")
    data object GuestStarted : AnalyticsEvent("guest_started")

    // Creation funnel
    data class ProjectCreated(val template: String, val bpmBucket: String) : AnalyticsEvent("project_created")
    data class TrackAdded(val type: String, val trackCountBucket: String) : AnalyticsEvent("track_added")
    data class TakeRecorded(val durationBucket: String, val latencyMsBucket: String) : AnalyticsEvent("take_recorded")
    data class MidiNoteEdited(val tool: String) : AnalyticsEvent("midi_edit")
    data class FxAdded(val plugin: String) : AnalyticsEvent("fx_added")
    data class InstrumentSelected(val instrument: String) : AnalyticsEvent("instrument_selected")
    data class LoopDropped(val packId: String?, val premium: Boolean) : AnalyticsEvent("loop_dropped")

    // Playback & perf
    data class PlaybackSession(val minutesBucket: String, val underruns: Int) : AnalyticsEvent("playback_session")
    data class EngineLatency(val roundTripMsBucket: String, val backend: String) : AnalyticsEvent("engine_latency")
    data class AudioDeviceSwitch(val from: String, val to: String) : AnalyticsEvent("device_switch")

    // Collaboration & social
    data class CollabSessionOpened(val collaboratorCount: Int) : AnalyticsEvent("collab_opened")
    data class ShareLinkCreated(val role: String) : AnalyticsEvent("share_created")
    data class PostPublished(val kind: String) : AnalyticsEvent("post_published")
    data class PostPlayed(val source: String) : AnalyticsEvent("post_played")

    // Export & monetization
    data class ExportCompleted(val format: String, val kind: String, val secondsBucket: String) : AnalyticsEvent("export_completed")
    data class ExportFailed(val format: String, val reason: String) : AnalyticsEvent("export_failed")
    data object PaywallShown : AnalyticsEvent("paywall_shown")
    data class PaywallConverted(val productId: String) : AnalyticsEvent("paywall_converted")
    data class PurchaseAttempted(val productId: String) : AnalyticsEvent("purchase_attempted")
    data class AdShown(val placement: String) : AnalyticsEvent("ad_shown")
}

interface AnalyticsSink {
    fun log(event: AnalyticsEvent)
    fun setUserProperty(name: String, value: String)
    fun setConsent(analytics: Boolean, ads: Boolean)
}

/** Fan-out sink: consent gate -> Firebase + any additional sinks. */
class AnalyticsHub(
    private val sinks: List<AnalyticsSink>,
    private val consentProvider: suspend () -> Boolean,
) {
    @Volatile private var consented: Boolean = false

    suspend fun refreshConsent() {
        consented = consentProvider()
        sinks.forEach { it.setConsent(consented, consented) }
    }

    fun log(event: AnalyticsEvent) {
        if (!consented) return // hard gate — nothing is buffered pre-consent
        sinks.forEach { runCatching { it.log(event) } }
    }

    fun setUserProperty(name: String, value: String) {
        if (!consented) return
        sinks.forEach { runCatching { it.setUserProperty(name, value) } }
    }
}
