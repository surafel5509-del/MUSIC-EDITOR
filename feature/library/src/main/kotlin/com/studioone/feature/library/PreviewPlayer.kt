package com.studioone.feature.library

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Short-preview playback for the browser, backed by Media3/ExoPlayer. */
@Singleton
class PreviewPlayer @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val player: ExoPlayer by lazy { ExoPlayer.Builder(context).build() }

    fun play(path: String) {
        player.stop()
        player.setMediaItem(MediaItem.fromPath(path))
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
    }

    fun release() {
        player.release()
    }
}
