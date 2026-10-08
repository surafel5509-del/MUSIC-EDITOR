package com.studioone.core.network.projects

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Row shape of the `projects` table in Postgres. */
@Serializable
data class ProjectRow(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("owner_id") val ownerId: String? = null,
    val tempo: Double = 120.0,
    @SerialName("time_sig_num") val timeSigNum: Int = 4,
    @SerialName("time_sig_den") val timeSigDen: Int = 4,
    @SerialName("musical_key") val musicalKey: String? = null,
    @SerialName("sample_rate") val sampleRate: Int = 44_100,
    val version: Int = 1,
    @SerialName("collab_session_id") val collabSessionId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Row shape of the `project_snapshots` table (full document payload). */
@Serializable
data class ProjectSnapshotRow(
    @SerialName("project_id") val projectId: String,
    val version: Int,
    @SerialName("payload_json") val payloadJson: String,
    @SerialName("created_at") val createdAt: String? = null,
)
