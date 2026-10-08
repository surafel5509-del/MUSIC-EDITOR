package com.studioone.core.network.collab

import com.google.common.truth.Truth.assertThat
import com.studioone.core.domain.model.ProjectId
import com.studioone.core.domain.model.collab.CollabOp
import org.junit.Test

class CrdtEngineTest {

    private val projectId = ProjectId("p1")

    private fun update(
        site: String,
        lamport: Long,
        clipId: String,
        field: String,
        value: String,
    ) = CollabOp.UpdateClipField("op-$site-$lamport", site, lamport, projectId, clipId, field, value)

    @Test
    fun `concurrent field updates resolve last-writer-wins by lamport`() {
        val engine = CrdtEngine(siteId = "local")
        val ops = listOf(
            update("a", lamport = 5, "clip1", "start_frame", "100"),
            update("b", lamport = 7, "clip1", "start_frame", "200"),
            update("a", lamport = 6, "clip1", "start_frame", "150"),
        )
        val state = engine.reduce(ops.shuffled())
        assertThat(state.clipFields["clip1"]?.get("start_frame")?.json).isEqualTo("200")
    }

    @Test
    fun `equal lamport resolves deterministically by site id`() {
        val engine = CrdtEngine(siteId = "local")
        val forward = engine.reduce(
            listOf(
                update("site-a", 3, "c", "gain", "0.5"),
                update("site-b", 3, "c", "gain", "0.9"),
            ),
        )
        val reversed = engine.reduce(
            listOf(
                update("site-b", 3, "c", "gain", "0.9"),
                update("site-a", 3, "c", "gain", "0.5"),
            ),
        )
        assertThat(forward.clipFields["c"]?.get("gain")).isEqualTo(reversed.clipFields["c"]?.get("gain"))
        assertThat(forward.clipFields["c"]?.get("gain")?.json).isEqualTo("0.9")
    }

    @Test
    fun `clock observe keeps site ahead of received ops`() {
        val engine = CrdtEngine(siteId = "local")
        engine.observe(41)
        assertThat(engine.tick()).isEqualTo(42)
    }

    @Test
    fun `independent fields do not clobber each other`() {
        val engine = CrdtEngine(siteId = "local")
        val state = engine.reduce(
            listOf(
                update("a", 1, "c", "gain", "0.5"),
                update("b", 2, "c", "start_frame", "100"),
            ),
        )
        assertThat(state.clipFields["c"]?.keys).containsExactly("gain", "start_frame")
    }
}
