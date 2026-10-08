package com.studioone.mobile.core.audio.di

import android.content.Context
import com.studioone.mobile.core.audio.DeviceProfileProvider
import com.studioone.mobile.core.audio.StripBudget
import com.studioone.mobile.core.common.DeviceProfile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    @Provides
    @Singleton
    fun provideDeviceProfileProvider(@ApplicationContext context: Context): DeviceProfileProvider {
        val perfClass = DeviceProfile.classify(context)
        return object : DeviceProfileProvider {
            override fun stripBudget(): StripBudget = when (perfClass) {
                // Engine pools scale with device class: LOW devices get fewer
                // strips & voices so the pre-allocated footprint stays < 30MB.
                DeviceProfile.PerformanceClass.LOW -> StripBudget(maxStrips = 32, maxInstrumentStrips = 8)
                DeviceProfile.PerformanceClass.MID -> StripBudget(maxStrips = 64, maxInstrumentStrips = 16)
                DeviceProfile.PerformanceClass.HIGH -> StripBudget(maxStrips = 96, maxInstrumentStrips = 32)
            }
        }
    }
}
