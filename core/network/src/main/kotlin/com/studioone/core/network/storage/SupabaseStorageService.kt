package com.studioone.core.network.storage

import com.studioone.core.common.error.StudioOneException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import javax.inject.Inject
import javax.inject.Singleton

/** Binary asset storage: samples, stems, exports, avatars. */
@Singleton
class SupabaseStorageService @Inject constructor(private val supabase: SupabaseClient) {

    companion object {
        const val BUCKET_SAMPLES = "samples"
        const val BUCKET_STEMS = "stems"
        const val BUCKET_EXPORTS = "exports"
    }

    suspend fun upload(bucket: String, path: String, bytes: ByteArray, contentType: String): String {
        try {
            supabase.storage.from(bucket).upload(path, bytes) {
                this.contentType = runCatching { ContentType.parse(contentType) }
                    .getOrDefault(ContentType.Application.OctetStream)
                upsert = true
            }
            return publicUrl(bucket, path)
        } catch (e: Exception) {
            throw StudioOneException.Network("Upload failed: ${e.message}", e)
        }
    }

    suspend fun download(bucket: String, path: String): ByteArray {
        try {
            return supabase.storage.from(bucket).downloadAuthenticated(path)
        } catch (e: Exception) {
            throw StudioOneException.Network("Download failed: ${e.message}", e)
        }
    }

    suspend fun delete(bucket: String, path: String) {
        supabase.storage.from(bucket).delete(listOf(path))
    }

    suspend fun publicUrl(bucket: String, path: String): String =
        supabase.storage.from(bucket).publicUrl(path)
}
