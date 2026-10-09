package com.studioone.core.data.repository

import android.content.Context
import com.studioone.core.common.error.StudioOneException
import com.studioone.core.database.dao.LibraryDao
import com.studioone.core.database.dao.PresetDao
import com.studioone.core.database.entity.LibraryItemEntity
import com.studioone.core.domain.model.library.LoopItem
import com.studioone.core.domain.model.library.LoopKind
import com.studioone.core.domain.model.library.LoopSource
import com.studioone.core.domain.model.library.Preset
import com.studioone.core.domain.model.library.SamplePack
import com.studioone.core.domain.model.music.MusicalKey
import com.studioone.core.domain.repository.LibraryFilter
import com.studioone.core.domain.repository.LibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Local-first content library. Bundled packs live in assets; cloud packs are
 * served by the backend and cached here after download.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryDao: LibraryDao,
    private val presetDao: PresetDao,
) : LibraryRepository {

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    override fun observeItems(filter: LibraryFilter): Flow<List<LoopItem>> =
        libraryDao.observeAll().map { rows ->
            // Local captures: public API properties from other modules cannot
            // be smart-cast across the null checks below.
            val bpmRange = filter.bpmRange
            val key = filter.key
            val packId = filter.packId
            rows.map { it.toDomain() }
                .filter { item ->
                    val itemBpm = item.bpm
                    val itemKey = item.key
                    (filter.query.isBlank() || item.name.contains(filter.query, true) ||
                        item.tags.any { it.contains(filter.query, true) }) &&
                        (filter.kinds.isEmpty() || item.kind in filter.kinds) &&
                        (!filter.favoritesOnly || item.favorite) &&
                        (packId == null || item.packId == packId) &&
                        (bpmRange == null || itemBpm == null || itemBpm in bpmRange) &&
                        (key == null || itemKey == null || itemKey == key)
                }
        }

    override fun observePacks(): Flow<List<SamplePack>> =
        libraryDao.observeAll().map { rows ->
            rows.groupBy { it.packId ?: "singles" }.map { (packId, items) ->
                SamplePack(
                    id = packId,
                    name = packId.replaceFirstChar { it.uppercase() },
                    description = "${items.size} items",
                    author = "StudioOne",
                    itemCount = items.size,
                    sizeBytes = items.sumOf { it.sizeBytes },
                    coverUrl = null,
                    installed = items.any { it.localPath != null },
                )
            }
        }

    override fun observePresets(targetId: String): Flow<List<Preset>> =
        presetDao.observeFor(targetId).map { rows ->
            rows.map { Preset(it.id, it.name, it.targetId, it.category, it.author, it.favorite, it.paramsJson) }
        }

    override suspend fun importLocalFile(uri: String, displayName: String): LoopItem =
        withContext(Dispatchers.IO) {
            val input = context.contentResolver.openInputStream(android.net.Uri.parse(uri))
                ?: throw StudioOneException.Storage("Cannot open file")
            val destDir = File(context.filesDir, "library").apply { mkdirs() }
            val dest = File(destDir, "${System.currentTimeMillis()}-$displayName")
            input.use { src -> dest.outputStream().use { src.copyTo(it) } }
            val item = LoopItem(
                id = java.util.UUID.randomUUID().toString(),
                name = displayName,
                kind = if (dest.length() < 200_000) LoopKind.ONE_SHOT else LoopKind.LOOP,
                source = LoopSource.LOCAL,
                localPath = dest.absolutePath,
                sizeBytes = dest.length(),
            )
            libraryDao.upsert(item.toEntity())
            item
        }

    override suspend fun download(item: LoopItem): LoopItem {
        // Milestone 2: download from CDN via SupabaseStorageService with progress.
        return item
    }

    override suspend fun deleteLocal(item: LoopItem) {
        item.localPath?.let { runCatching { File(it).delete() } }
        libraryDao.delete(item.id)
    }

    override suspend fun toggleFavorite(item: LoopItem) {
        libraryDao.upsert(item.copy(favorite = !item.favorite).toEntity())
    }

    override suspend fun analyze(item: LoopItem): LoopItem {
        // BPM/key detection is provided by :audio analysis; wired in feature:library.
        return item
    }

    private fun LibraryItemEntity.toDomain() = LoopItem(
        id = id, name = name,
        kind = runCatching { LoopKind.valueOf(kind) }.getOrDefault(LoopKind.ONE_SHOT),
        source = runCatching { LoopSource.valueOf(source) }.getOrDefault(LoopSource.LOCAL),
        bpm = bpm,
        key = musicalKey?.let { raw -> runCatching { json.decodeFromString(MusicalKey.serializer(), raw) }.getOrNull() },
        durationMs = durationMs,
        tags = if (tags.isBlank()) emptyList() else tags.split(","),
        packId = packId,
        localPath = localPath,
        remoteUrl = remoteUrl,
        sizeBytes = sizeBytes,
        favorite = favorite,
    )

    private fun LoopItem.toEntity() = LibraryItemEntity(
        id = id, name = name, kind = kind.name, source = source.name,
        bpm = bpm, musicalKey = key?.let { json.encodeToString(MusicalKey.serializer(), it) }, durationMs = durationMs,
        tags = tags.joinToString(","), packId = packId, localPath = localPath,
        remoteUrl = remoteUrl, sizeBytes = sizeBytes, favorite = favorite,
    )
}
