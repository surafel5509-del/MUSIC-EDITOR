package com.studioone.mobile.core.realtime

import com.google.common.truth.Truth.assertThat
import com.studioone.mobile.core.model.CollabOp
import com.studioone.mobile.core.model.Comment
import com.studioone.mobile.core.model.CommentId
import com.studioone.mobile.core.model.JsonValueLike
import com.studioone.mobile.core.model.OpEnvelope
import com.studioone.mobile.core.model.Project
import com.studioone.mobile.core.model.ProjectField
import com.studioone.mobile.core.model.ProjectId
import com.studioone.mobile.core.model.ProjectTemplate
import com.studioone.mobile.core.model.Track
import com.studioone.mobile.core.model.TrackField
import com.studioone.mobile.core.model.TrackId
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.realtime.crdt.HlcTimestamp
import com.studioone.mobile.core.realtime.crdt.HybridClock
import com.studioone.mobile.core.realtime.crdt.LwwRegister
import com.studioone.mobile.core.realtime.crdt.OrSet
import com.studioone.mobile.core.realtime.crdt.ProjectCrdt
import kotlinx.datetime.Clock
import org.junit.Test

class HybridClockTest {

    @Test
    fun `clock is monotonic even with a frozen wall clock`() {
        val clock = HybridClock("nodeA", timeSource = { 1000L })
        val t1 = clock.now()
        val t2 = clock.now()
        val t3 = clock.now()
        assertThat(t2).isGreaterThan(t1)
        assertThat(t3).isGreaterThan(t2)
    }

    @Test
    fun `receive advances past remote stamp`() {
        val clock = HybridClock("nodeA", timeSource = { 1000L })
        val remote = HlcTimestamp(5000L, 7, "nodeB")
        val after = clock.receive(remote)
        assertThat(after.millis).isEqualTo(5000L)
        assertThat(after.counter).isEqualTo(8)
        assertThat(after.nodeId).isEqualTo("nodeA")
    }

    @Test
    fun `total order breaks ties by counter then node id`() {
        val a = HlcTimestamp(100, 1, "aaa")
        val b = HlcTimestamp(100, 1, "bbb")
        val c = HlcTimestamp(100, 2, "aaa")
        assertThat(c).isGreaterThan(a)
        assertThat(b).isGreaterThan(a)
    }

    @Test
    fun `encode decode roundtrip`() {
        val t = HlcTimestamp(1_700_000_000_123L, 42, "node-uuid-1")
        assertThat(HlcTimestamp.decode(t.encode())).isEqualTo(t)
    }
}

class LwwRegisterTest {
    @Test
    fun `concurrent writes converge to higher stamp`() {
        val r1 = LwwRegister("local", HlcTimestamp(100, 0, "a"))
        val r2 = LwwRegister("remote", HlcTimestamp(100, 0, "b"))
        // Same millis+counter: nodeId decides, identically on both replicas.
        assertThat(r1.merge(r2).value).isEqualTo("remote")
        assertThat(r2.merge(r1).value).isEqualTo("remote")
    }
}

class OrSetTest {
    @Test
    fun `add wins over concurrent remove`() {
        val replicaA = OrSet<String>()
        val replicaB = OrSet<String>()

        // Both observe the original add.
        replicaA.add("clip1", "v1", "tag0")
        replicaB.add("clip1", "v1", "tag0")

        // A removes (observing only tag0); B concurrently re-adds with tag1.
        replicaA.remove("clip1", setOf("tag0"))
        replicaB.add("clip1", "v2", "tag1")

        val mergedAB = replicaA.merge(replicaB)
        val mergedBA = replicaB.merge(replicaA)
        // Add-wins: element present on both merges, same value.
        assertThat(mergedAB.contains("clip1")).isTrue()
        assertThat(mergedBA.contains("clip1")).isTrue()
        assertThat(mergedAB.get("clip1")).isEqualTo(mergedBA.get("clip1"))
    }

    @Test
    fun `causal remove deletes element`() {
        val set = OrSet<String>()
        set.add("t1", "value", "tagA")
        set.remove("t1", set.tagsOf("t1"))
        assertThat(set.contains("t1")).isFalse()
    }
}

class ProjectCrdtTest {

    private fun baseProject() = com.studioone.mobile.core.domain.EditorActions.newProject(
        "Session", ProjectTemplate.EMPTY, 120.0, UserId("u1"),
    )

    private fun envelope(opId: String, author: String, lamport: Long, op: CollabOp) = OpEnvelope(
        opId = opId, projectId = ProjectId("p1"), authorId = UserId(author),
        lamport = lamport, wallClock = Clock.System.now(), payload = op,
    )

    @Test
    fun `duplicate op ids are applied once`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("n1"), "n1")
        val env = envelope("op-1", "u2", 5, CollabOp.UpdateProjectField(ProjectField.NAME, JsonValueLike.Str("Renamed")))
        assertThat(crdt.apply(env)).isTrue()
        assertThat(crdt.apply(env)).isFalse() // dedupe
    }

    @Test
    fun `concurrent field edits converge regardless of delivery order`() {
        val opA = envelope("op-a", "alice", 100, CollabOp.UpdateProjectField(ProjectField.NAME, JsonValueLike.Str("Alice's name")))
        val opB = envelope("op-b", "bob", 100, CollabOp.UpdateProjectField(ProjectField.NAME, JsonValueLike.Str("Bob's name")))

        val crdt1 = ProjectCrdt(ProjectId("p1"), HybridClock("r1"), "r1")
        crdt1.apply(opA); crdt1.apply(opB)
        val crdt2 = ProjectCrdt(ProjectId("p1"), HybridClock("r2"), "r2")
        crdt2.apply(opB); crdt2.apply(opA)

        val view1 = crdt1.materialize(baseProject())
        val view2 = crdt2.materialize(baseProject())
        // Same stamp (lamport 100): tie broken deterministically by author id.
        assertThat(view1.name).isEqualTo(view2.name)
        assertThat(view1.name).isEqualTo("Bob's name") // "bob" > "alice" lexicographically
    }

    @Test
    fun `track field updates do not clobber other fields`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("n1"), "n1")
        val track = Track(TrackId("t1"), "Guitar", volumeDb = -6f, pan = 0.3f)
        crdt.apply(envelope("op-1", "u1", 10, CollabOp.AddTrack(track)))
        crdt.apply(envelope("op-2", "u2", 20,
            CollabOp.UpdateTrackField(TrackId("t1"), TrackField.VOLUME, JsonValueLike.Num(-12.0))))
        crdt.apply(envelope("op-3", "u3", 30,
            CollabOp.UpdateTrackField(TrackId("t1"), TrackField.PAN, JsonValueLike.Num(-0.5))))

        val view = crdt.materialize(baseProject())
        val materialized = view.tracks.first { it.id == TrackId("t1") }
        assertThat(materialized.volumeDb).isEqualTo(-12f)
        assertThat(materialized.pan).isEqualTo(-0.5f)
        assertThat(materialized.name).isEqualTo("Guitar") // untouched
    }

    @Test
    fun `remove track hides it in the materialized view`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("n1"), "n1")
        val track = Track(TrackId("t1"), "Guitar")
        crdt.apply(envelope("op-1", "u1", 10, CollabOp.AddTrack(track)))
        crdt.apply(envelope("op-2", "u1", 20, CollabOp.RemoveTrack(TrackId("t1"))))
        val view = crdt.materialize(baseProject())
        assertThat(view.tracks.none { it.id == TrackId("t1") }).isTrue()
    }

    @Test
    fun `local apply returns broadcastable envelope`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("me"), "me")
        val env = crdt.applyLocal(CollabOp.UpdateProjectField(ProjectField.NAME, JsonValueLike.Str("Live edit")), "me")
        assertThat(env.opId).isNotEmpty()
        assertThat(env.authorId.value).isEqualTo("me")
        assertThat(env.lamport).isGreaterThan(0)
    }

    @Test
    fun `comments accumulate through add and resolve`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("n1"), "n1")
        val comment = Comment(
            id = CommentId("c1"), projectId = ProjectId("p1"), authorId = UserId("u1"),
            authorName = "Ana", text = "Love this take", anchorFrame = 48_000,
            createdAt = Clock.System.now(),
        )
        crdt.apply(envelope("op-1", "u1", 10, CollabOp.AddComment(comment)))
        crdt.apply(envelope("op-2", "u2", 20, CollabOp.ResolveComment(CommentId("c1"), true)))
        // Resolution is part of the grow-only comment set (latest tag wins in values()).
        assertThat(crdt.isApplied("op-2")).isTrue()
    }

    @Test
    fun `prune keeps dedupe set bounded`() {
        val crdt = ProjectCrdt(ProjectId("p1"), HybridClock("n1"), "n1")
        repeat(10_000) { i ->
            crdt.apply(envelope("op-$i", "u1", i.toLong(),
                CollabOp.UpdateProjectField(ProjectField.NAME, JsonValueLike.Str("n$i"))))
        }
        crdt.pruneAppliedOps(keepLast = 100)
        // Old ids forgotten, but re-applying a pruned id would duplicate — the
        // server's lamport window prevents that in practice (documented).
        assertThat(crdt.isApplied("op-9999")).isTrue()
        assertThat(crdt.isApplied("op-0")).isFalse()
    }
}
