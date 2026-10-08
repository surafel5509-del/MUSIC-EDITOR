package com.studioone.core.domain.repository

import com.studioone.core.domain.model.library.LoopItem
import com.studioone.core.domain.model.library.LoopKind
import com.studioone.core.domain.model.library.Preset
import com.studioone.core.domain.model.library.SamplePack
import com.studioone.core.domain.model.music.MusicalKey
import kotlinx.coroutines.flow.Flow

data class LibraryFilter(
    val query: String = "",
    val kinds: Set<LoopKind> = emptySet(),
    val bpmRange: ClosedFloatingPointRange<Double>? = null,
    val key: MusicalKey? = null,
    val favoritesOnly: Boolean = false,
    val packId: String? = null,
)

interface LibraryRepository {
    fun observeItems(filter: LibraryFilter): Flow<List<LoopItem>>
    fun observePacks(): Flow<List<SamplePack>>
    fun observePresets(targetId: String): Flow<List<Preset>>

    suspend fun importLocalFile(uri: String, displayName: String): LoopItem
    suspend fun download(item: LoopItem): LoopItem
    suspend fun deleteLocal(item: LoopItem)
    suspend fun toggleFavorite(item: LoopItem)
    suspend fun analyze(item: LoopItem): LoopItem
}
