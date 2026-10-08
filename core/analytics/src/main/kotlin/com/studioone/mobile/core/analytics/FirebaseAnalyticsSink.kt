package com.studioone.mobile.core.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Firebase implementation of the sink. Crashlytics custom keys mirror the
 * last few analytics events ("breadcrumbs") to give crash reports session
 * context without PII.
 */
@Singleton
class FirebaseAnalyticsSink @Inject constructor(
    @ApplicationContext context: Context,
) : AnalyticsSink {

    private val firebase: FirebaseAnalytics? = runCatching { FirebaseAnalytics.getInstance(context) }.getOrNull()
    private val crashlytics: FirebaseCrashlytics? = runCatching { FirebaseCrashlytics.getInstance() }.getOrNull()

    override fun log(event: AnalyticsEvent) {
        val bundle = Bundle().apply {
            // Reflect data-class properties into the bundle generically.
            event.javaClass.declaredFields.filter { !it.isSynthetic }.forEach { field ->
                field.isAccessible = true
                when (val v = field.get(event)) {
                    is String -> putString(field.name, v)
                    is Int -> putInt(field.name, v)
                    is Long -> putLong(field.name, v)
                    is Boolean -> putBoolean(field.name, v)
                    is Double -> putDouble(field.name, v)
                    else -> Unit
                }
            }
        }
        firebase?.logEvent(event.name, bundle)
        crashlytics?.setCustomKey("last_event", event.name)
    }

    override fun setUserProperty(name: String, value: String) {
        firebase?.setUserProperty(name, value)
    }

    override fun setConsent(analytics: Boolean, ads: Boolean) {
        firebase?.setAnalyticsCollectionEnabled(analytics)
        crashlytics?.setCrashlyticsCollectionEnabled(analytics)
    }
}
