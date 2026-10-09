package com.studioone.core.network.collab

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabOp
import org.junit.Test

class CollabOpSerializerTest {

    @Test
    fun `round trips every op type`() {
        val projectId = ProjectId("p1")
        val ops: List<CollabOp> = listOf(
            CollabOp.AddTrack("1", "s", 1, projectId, "{\"name\":\"Vocals\"}", 2),
            CollabOp.RemoveTrack("2", "s", 2, projectId, "t1"),
            CollabOp.UpdateTrackField("3", "s", 3, projectId, "t1", "volume", "0.7"),
            CollabOp.AddClip("4", "s", 4, projectId, "{\"id\":\"c1\"}"),
            CollabOp.UpdateClipField("5", "s", 5, projectId, "c1", "gain", "1.0"),
            CollabOp.RemoveClip("6", "s", 6, projectId, "c1"),
            CollabOp.ChatMessage("7", "s", 7, projectId, "hello", 123L),
        )
        ops.forEach { op ->
            val decoded = CollabOpSerializer.decode(CollabOpSerializer.encode(op))
            assertThat(decoded).isEqualTo(op)
        }
    }
}
