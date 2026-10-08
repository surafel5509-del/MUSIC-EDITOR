package com.studioone.core.data.repository

import android.content.Context
import com.studioone.core.domain.model.ProjectTemplate
import com.studioone.core.domain.model.TemplateCategory
import com.studioone.core.domain.model.TimeSignature
import com.studioone.core.domain.model.TrackSpec
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.model.music.MusicalKey
import com.studioone.core.domain.repository.TemplateRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Loads project templates bundled in `assets/templates/templates.json`. */
@Singleton
class TemplateRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : TemplateRepository {

    @Serializable
    private data class TemplateFile(
        val id: String,
        val name: String,
        val description: String,
        val category: String,
        val tempo: Double,
        val timeSigNum: Int = 4,
        val timeSigDen: Int = 4,
        val keyRoot: Int? = null,
        val keyScale: String? = null,
        val tracks: List<TrackSpecFile>,
    )

    @Serializable
    private data class TrackSpecFile(
        val name: String,
        val type: String,
        val colorIndex: Int = 0,
        val instrumentId: String? = null,
        val effectChainIds: List<String> = emptyList(),
        val armed: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: List<ProjectTemplate>? = null

    override suspend fun getTemplates(): List<ProjectTemplate> {
        cache?.let { return it }
        val raw = context.assets.open("templates/templates.json").bufferedReader().use { it.readText() }
        val templates = json.decodeFromString(ListSerializer(TemplateFile.serializer()), raw).map { file ->
            ProjectTemplate(
                id = file.id,
                name = file.name,
                description = file.description,
                category = runCatching { TemplateCategory.valueOf(file.category) }
                    .getOrDefault(TemplateCategory.BLANK),
                tempo = file.tempo,
                timeSignature = TimeSignature(file.timeSigNum, file.timeSigDen),
                key = file.keyRoot?.let { root ->
                    MusicalKey(root = root, scale = file.keyScale ?: "MAJOR")
                },
                tracks = file.tracks.map { spec ->
                    TrackSpec(
                        name = spec.name,
                        type = runCatching { TrackType.valueOf(spec.type) }.getOrDefault(TrackType.AUDIO),
                        colorIndex = spec.colorIndex,
                        instrumentId = spec.instrumentId,
                        effectChainIds = spec.effectChainIds,
                        armed = spec.armed,
                    )
                },
            )
        }
        cache = templates
        return templates
    }

    override suspend fun getTemplate(id: String): ProjectTemplate? =
        getTemplates().firstOrNull { it.id == id }
}
