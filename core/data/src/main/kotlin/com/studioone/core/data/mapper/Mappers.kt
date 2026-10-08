package com.studioone.core.data.mapper

import com.studioone.core.database.entity.AudioClipEntity
import com.studioone.core.database.entity.MidiClipEntity
import com.studioone.core.database.entity.ProjectEntity
import com.studioone.core.database.entity.TrackEntity
import com.studioone.core.domain.model.AudioClip
import com.studioone.core.domain.model.AutomationLane
import com.studioone.core.domain.model.ClipId
import com.studioone.core.domain.model.EffectInstance
import com.studioone.core.domain.model.MidiClip
import com.studioone.core.domain.model.MidiNote
import com.studioone.core.domain.model.OutputBus
import com.studioone.core.domain.model.Project
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.ProjectStatus
import com.studioone.core.domain.model.SendInstance
import com.studioone.core.domain.model.TimeSignature
import com.studioone.core.domain.model.Track
import com.studioone.core.domain.model.TrackId
import com.studioone.core.domain.model.TrackType
import com.studioone.core.domain.model.UserId
import com.studioone.core.domain.model.music.MusicalKey
import kotlinx.datetime.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON (de)serialization for nested track/clip values stored in columns. */
internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private val effectListSerializer = ListSerializer(EffectInstance.serializer())
private val sendListSerializer = ListSerializer(SendInstance.serializer())
private val automationListSerializer = ListSerializer(AutomationLane.serializer())
private val noteListSerializer = ListSerializer(MidiNote.serializer())

fun ProjectEntity.toDomain() = Project(
    id = ProjectId(id),
    name = name,
    description = description,
    templateId = templateId,
    tempo = tempo,
    timeSignature = TimeSignature(timeSigNum, timeSigDen),
    key = musicalKey?.let { runCatching { json.decodeFromString<MusicalKey>(it) }.getOrNull() },
    sampleRate = sampleRate,
    status = runCatching { ProjectStatus.valueOf(status) }.getOrDefault(ProjectStatus.ACTIVE),
    colorIndex = colorIndex,
    version = version,
    createdAt = Instant.fromEpochMilliseconds(createdAt),
    updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    durationFrames = durationFrames,
    ownerId = ownerId?.let(::UserId),
    collabSessionId = collabSessionId,
)

fun Project.toEntity() = ProjectEntity(
    id = id.value,
    name = name,
    description = description,
    templateId = templateId,
    tempo = tempo,
    timeSigNum = timeSignature.numerator,
    timeSigDen = timeSignature.denominator,
    musicalKey = key?.let { json.encodeToString(MusicalKey.serializer(), it) },
    sampleRate = sampleRate,
    status = status.name,
    colorIndex = colorIndex,
    version = version,
    createdAt = createdAt.toEpochMilliseconds(),
    updatedAt = updatedAt.toEpochMilliseconds(),
    durationFrames = durationFrames,
    ownerId = ownerId?.value,
    collabSessionId = collabSessionId,
)

fun TrackEntity.toDomain() = Track(
    id = TrackId(id),
    projectId = ProjectId(projectId),
    name = name,
    type = runCatching { TrackType.valueOf(type) }.getOrDefault(TrackType.AUDIO),
    colorIndex = colorIndex,
    orderIndex = orderIndex,
    volume = volume,
    pan = pan,
    muted = muted,
    soloed = soloed,
    armed = armed,
    frozen = frozen,
    inputId = inputId,
    outputBus = runCatching { OutputBus.valueOf(outputBus) }.getOrDefault(OutputBus.MASTER),
    instrumentId = instrumentId,
    inserts = runCatching { json.decodeFromString(effectListSerializer, insertsJson) }.getOrDefault(emptyList()),
    sends = runCatching { json.decodeFromString(sendListSerializer, sendsJson) }.getOrDefault(emptyList()),
    automation = runCatching { json.decodeFromString(automationListSerializer, automationJson) }.getOrDefault(emptyList()),
)

fun Track.toEntity() = TrackEntity(
    id = id.value,
    projectId = projectId.value,
    name = name,
    type = type.name,
    colorIndex = colorIndex,
    orderIndex = orderIndex,
    volume = volume,
    pan = pan,
    muted = muted,
    soloed = soloed,
    armed = armed,
    frozen = frozen,
    inputId = inputId,
    outputBus = outputBus.name,
    instrumentId = instrumentId,
    insertsJson = json.encodeToString(effectListSerializer, inserts),
    sendsJson = json.encodeToString(sendListSerializer, sends),
    automationJson = json.encodeToString(automationListSerializer, automation),
)

fun AudioClipEntity.toDomain() = AudioClip(
    id = ClipId(id),
    trackId = TrackId(trackId),
    name = name,
    startFrame = startFrame,
    lengthFrames = lengthFrames,
    gain = gain,
    fadeInFrames = fadeInFrames,
    fadeOutFrames = fadeOutFrames,
    colorIndex = colorIndex,
    locked = locked,
    filePath = filePath,
    sourceOffsetFrames = sourceOffsetFrames,
    sourceLengthFrames = sourceLengthFrames,
    reversed = reversed,
    playbackRate = playbackRate,
    normalized = normalized,
)

fun AudioClip.toEntity(projectId: ProjectId) = AudioClipEntity(
    id = id.value,
    projectId = projectId.value,
    trackId = trackId.value,
    name = name,
    startFrame = startFrame,
    lengthFrames = lengthFrames,
    gain = gain,
    fadeInFrames = fadeInFrames,
    fadeOutFrames = fadeOutFrames,
    colorIndex = colorIndex,
    locked = locked,
    filePath = filePath,
    sourceOffsetFrames = sourceOffsetFrames,
    sourceLengthFrames = sourceLengthFrames,
    reversed = reversed,
    playbackRate = playbackRate,
    normalized = normalized,
)

fun MidiClipEntity.toDomain() = MidiClip(
    id = ClipId(id),
    trackId = TrackId(trackId),
    name = name,
    startFrame = startFrame,
    lengthFrames = lengthFrames,
    gain = gain,
    fadeInFrames = fadeInFrames,
    fadeOutFrames = fadeOutFrames,
    colorIndex = colorIndex,
    locked = locked,
    notes = runCatching { json.decodeFromString(noteListSerializer, notesJson) }.getOrDefault(emptyList()),
    programId = programId,
)

fun MidiClip.toEntity(projectId: ProjectId) = MidiClipEntity(
    id = id.value,
    projectId = projectId.value,
    trackId = trackId.value,
    name = name,
    startFrame = startFrame,
    lengthFrames = lengthFrames,
    gain = gain,
    fadeInFrames = fadeInFrames,
    fadeOutFrames = fadeOutFrames,
    colorIndex = colorIndex,
    locked = locked,
    notesJson = json.encodeToString(noteListSerializer, notes),
    programId = programId,
)
