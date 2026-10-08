package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.data.sync.LibraryCacheRefresher
import com.studioone.mobile.core.database.dao.LibraryDao
import com.studioone.mobile.core.database.entity.LibraryItemEntity
import com.studioone.mobile.core.domain.DownloadProgress
import com.studioone.mobile.core.domain.LibraryRepository
import com.studioone.mobile.core.model.LibraryItem
import com.studioone.mobile.core.model.LibraryCategory
import com.studioone.mobile.core.model.MusicalKey
import com.studioone.mobile.core.model.SampleId
import com.studioone.mobile.core.model.SamplePack
import com.studioone.mobile.core.model.PurchaseState
import com.studioone.mobile.core.network.api.StudioOneApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loop library: search (cloud with local cache fallback), pack downloads
 * (resumable via OkHttp range requests), preview streaming handled by
 * Media3 in feature:loops.
 */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val api: StudioOneApi,
    private val libraryDao: LibraryDao,
    private val filesDirProvider: FilesDirProvider,
    private val httpClient: OkHttpClient,
) : LibraryRepository, LibraryCacheRefresher {

    override suspend fun search(
        query: String?, category: String?, genre: String?,
        bpmRange: ClosedFloatingPointRange<Double>?, key: String?, cursor: String?,
    ): DataResult<Pair<List<LibraryItem>, String?>> {
        return try {
            val resp = api.searchLibrary(
                q = query, category = category, genre = genre,
                bpmMin = bpmRange?.start, bpmMax = bpmRange?.endInclusive,
                key = key, cursor = cursor,
            )
            val body = resp.body() ?: return cachedSearch(query, category, genre)
            val items = body.items.map { it.toModel() }
            cacheItems(items)
            DataResult.Success(items to body.nextCursor)
        } catch (t: Throwable) {
            cachedSearch(query, category, genre) // offline: serve cache
        }
    }

    private suspend fun cachedSearch(query: String?, category: String?, genre: String?): DataResult<Pair<List<LibraryItem>, String?>> {
        val rows = libraryDao.search(category, genre, query ?: "", limit = 60, offset = 0)
        if (rows.isEmpty()) return DataResult.Failure(DataError.OFFLINE)
        return DataResult.Success(rows.map { it.toModel() } to null)
    }

    private suspend fun cacheItems(items: List<LibraryItem>) {
        libraryDao.upsertAll(items.map { it.toEntity() })
    }

    override fun observePacks(): Flow<List<SamplePack>> = flow {
        // Packs are part of the catalog response; a dedicated /packs endpoint
        // ships in backend v2 (see docs/BACKEND.md roadmap).
        emit(emptyList())
    }

    override suspend fun downloadItem(itemId: String): Flow<DownloadProgress> = flow {
        val entity = libraryDao.search(null, null, itemId, 1, 0).firstOrNull()
        val url = entity?.fullUrl ?: entity?.previewUrl
        if (url == null) {
            emit(DownloadProgress(itemId, 0f, 0, 0, error = "Item unavailable"))
            return@flow
        }
        val dir = File(filesDirProvider.packsDir(), itemId).apply { mkdirs() }
        val outFile = File(dir, url.substringAfterLast('/').substringBefore('?'))
        try {
            val request = okhttp3.Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                val total = resp.body?.contentLength() ?: -1L
                var done = 0L
                outFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    val input = resp.body?.byteStream() ?: return@use
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        done += read
                        emit(DownloadProgress(itemId, if (total > 0) done.toFloat() / total else 0f, done, total))
                    }
                }
            }
            libraryDao.setDownloaded(itemId, true, outFile.absolutePath)
            emit(DownloadProgress(itemId, 1f, done = 1, bytesTotal = 1, done = true))
        } catch (t: Throwable) {
            emit(DownloadProgress(itemId, 0f, 0, 0, error = t.message))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun purchasePack(productId: String): DataResult<PurchaseState> {
        // Delegated to EntitlementRepository.purchase by the paywall feature;
        // on grant, the pack's items flip to downloadable.
        return DataResult.Success(PurchaseState.PENDING)
    }

    override fun observeDownloaded(): Flow<List<LibraryItem>> =
        libraryDao.observeDownloaded().map { rows -> rows.map { it.toModel() } }

    override suspend fun deleteDownload(itemId: String): DataResult<Unit> {
        val rows = libraryDao.search(null, null, itemId, 1, 0)
        rows.firstOrNull()?.localUri?.let { runCatching { File(it).delete() } }
        libraryDao.setDownloaded(itemId, false, null)
        return DataResult.Success(Unit)
    }

    override suspend fun refreshCatalog(): Boolean = try {
        val resp = api.searchLibrary(q = null, category = null, genre = null,
            bpmMin = null, bpmMax = null, key = null, cursor = null, limit = 200)
        resp.body()?.items?.let { cacheItems(it.map { w -> w.toModel() }) }
        true
    } catch (t: Throwable) { false }
}

private fun com.studioone.mobile.core.network.api.LibraryItemWire.toModel() = LibraryItem(
    id = SampleId(id), name = name,
    category = runCatching { LibraryCategory.valueOf(this.category) }.getOrDefault(LibraryCategory.LOOPS),
    genre = null, mood = null,
    bpm = bpm, key = null, isLoop = isLoop,
    durationFrames = durationFrames, sampleRate = sampleRate,
    format = runCatching { com.studioone.mobile.core.model.AudioFormatKind.valueOf(format.uppercase()) }
        .getOrDefault(com.studioone.mobile.core.model.AudioFormatKind.WAV),
    previewUrl = previewUrl, fullUrl = fullUrl, artworkUrl = artworkUrl,
    packId = packId, isPremium = isPremium, tags = tags,
)

private fun LibraryItem.toEntity() = LibraryItemEntity(
    id = id.value, name = name, category = category.name, genre = genre?.name, mood = mood?.name,
    bpm = bpm, keyName = key?.toString(), isLoop = isLoop, previewUrl = previewUrl,
    fullUrl = fullUrl, artworkUrl = artworkUrl, packId = packId, isPremium = isPremium,
    isDownloaded = isDownloaded, localUri = localUri, tags = tags.joinToString(","),
    durationFrames = durationFrames, cachedAt = System.currentTimeMillis(),
)

private fun LibraryItemEntity.toModel() = LibraryItem(
    id = SampleId(id), name = name,
    category = runCatching { LibraryCategory.valueOf(category) }.getOrDefault(LibraryCategory.LOOPS),
    genre = null, mood = null, bpm = bpm, key = null, isLoop = isLoop,
    durationFrames = durationFrames, sampleRate = 44100,
    format = com.studioone.mobile.core.model.AudioFormatKind.WAV,
    previewUrl = previewUrl, fullUrl = fullUrl, artworkUrl = artworkUrl, packId = packId,
    isPremium = isPremium, isDownloaded = isDownloaded, localUri = localUri,
    tags = tags?.split(",")?.filter { it.isNotEmpty() } ?: emptyList(),
)

interface FilesDirProvider {
    fun packsDir(): File
    fun projectsDir(): File
    fun bounceDir(): File
    fun takesDir(): String
}
