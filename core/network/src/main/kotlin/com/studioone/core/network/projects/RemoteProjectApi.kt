package com.studioone.core.network.projects

import com.studioone.core.common.error.StudioOneException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper over the `projects` / `project_snapshots` Postgres tables. */
@Singleton
class RemoteProjectApi @Inject constructor(private val supabase: SupabaseClient) {

    private companion object {
        const val PROJECTS = "projects"
        const val SNAPSHOTS = "project_snapshots"
    }

    suspend fun upsertProject(row: ProjectRow) {
        try {
            supabase.from(PROJECTS).upsert(row) {
                onConflict = "id"
            }
        } catch (e: Exception) {
            throw StudioOneException.Network("Failed to sync project: ${e.message}", e)
        }
    }

    suspend fun fetchProject(projectId: String): ProjectRow? =
        try {
            supabase.from(PROJECTS)
                .select { filter { eq("id", projectId) } }
                .decodeSingleOrNull<ProjectRow>()
        } catch (e: Exception) {
            throw StudioOneException.Network("Failed to fetch project: ${e.message}", e)
        }

    suspend fun pushSnapshot(snapshot: ProjectSnapshotRow) {
        try {
            supabase.from(SNAPSHOTS).insert(snapshot)
        } catch (e: Exception) {
            throw StudioOneException.Network("Failed to push snapshot: ${e.message}", e)
        }
    }

    suspend fun fetchLatestSnapshot(projectId: String): ProjectSnapshotRow? =
        try {
            supabase.from(SNAPSHOTS)
                .select {
                    filter { eq("project_id", projectId) }
                    order("version", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<ProjectSnapshotRow>()
        } catch (e: Exception) {
            throw StudioOneException.Network("Failed to fetch snapshot: ${e.message}", e)
        }

    suspend fun deleteProject(projectId: String) {
        try {
            supabase.from(PROJECTS).delete { filter { eq("id", projectId) } }
        } catch (e: Exception) {
            throw StudioOneException.Network("Failed to delete remote project: ${e.message}", e)
        }
    }
}
