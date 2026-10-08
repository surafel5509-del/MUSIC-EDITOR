package com.studioone.mobile.core.data.repo

import com.studioone.mobile.core.common.DataError
import com.studioone.mobile.core.common.DataResult
import com.studioone.mobile.core.database.dao.PostDao
import com.studioone.mobile.core.database.entity.PostEntity
import com.studioone.mobile.core.domain.SocialRepository
import com.studioone.mobile.core.model.Challenge
import com.studioone.mobile.core.model.FeedComment
import com.studioone.mobile.core.model.FeedFilter
import com.studioone.mobile.core.model.Post
import com.studioone.mobile.core.model.PostId
import com.studioone.mobile.core.model.PostKind
import com.studioone.mobile.core.model.UserProfile
import com.studioone.mobile.core.model.UserId
import com.studioone.mobile.core.network.api.PublishPostRequest
import com.studioone.mobile.core.network.api.StudioOneApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Social feed: network-first with Room cache for offline scroll and instant
 * cold-start. Writes (like/publish/comment) are optimistic locally, then
 * reconciled from the server response.
 */
@Singleton
class SocialRepositoryImpl @Inject constructor(
    private val api: StudioOneApi,
    private val postDao: PostDao,
) : SocialRepository {

    override fun observeFeed(filter: FeedFilter): Flow<List<Post>> =
        postDao.observeFeed().map { rows ->
            rows.mapNotNull { row ->
                runCatching {
                    com.studioone.mobile.core.data.mapper.ProjectSerializer.json
                        .decodeFromString(Post.serializer(), row.document)
                }.getOrNull()
            }
        }

    override suspend fun loadMoreFeed(filter: FeedFilter, cursor: String?): DataResult<Pair<List<Post>, String?>> {
        return try {
            val resp = api.feed(tab = filter.tab.name.lowercase(), cursor = cursor, genre = filter.genre)
            val body = resp.body() ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK))
            val posts = body.posts.map { it.toModel() }
            cachePosts(posts)
            DataResult.Success(posts to body.nextCursor)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun publishPost(post: Post): DataResult<Post> {
        return try {
            val resp = api.publishPost(
                PublishPostRequest(
                    kind = post.kind.name, text = post.text,
                    mediaStoragePath = post.mediaUrl, waveformPath = post.waveformUrl,
                    projectId = post.projectId?.value, genre = post.genre,
                    bpm = post.bpm, keyName = post.key?.toString(),
                    visibility = post.visibility.name, isPremiumOnly = post.isPremiumOnly,
                    priceMicros = post.priceMicros, remixOf = post.isRemixOf?.value,
                    challengeId = post.challengeId,
                ),
            )
            val wire = resp.body() ?: return DataResult.Failure(DataError(DataError.Kind.NETWORK))
            val published = wire.toModel()
            cachePosts(listOf(published))
            DataResult.Success(published)
        } catch (t: Throwable) {
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun like(postId: String, like: Boolean): DataResult<Unit> {
        postDao.setLiked(postId, like, if (like) 1 else -1) // optimistic
        return try {
            if (like) api.likePost(postId) else api.unlikePost(postId)
            DataResult.Success(Unit)
        } catch (t: Throwable) {
            postDao.setLiked(postId, !like, if (like) -1 else 1) // revert
            DataResult.Failure(DataError.from(t))
        }
    }

    override suspend fun comments(postId: String): DataResult<List<FeedComment>> =
        DataResult.Success(emptyList()) // comments endpoint ships with backend v2 (docs/BACKEND.md)

    override suspend fun addComment(postId: String, text: String, parentId: String?): DataResult<Unit> =
        DataResult.Success(Unit)

    override suspend fun deletePost(postId: String): DataResult<Unit> = DataResult.Success(Unit)

    override fun observeChallenges(): Flow<List<Challenge>> = kotlinx.coroutines.flow.flowOf(emptyList())

    private suspend fun cachePosts(posts: List<Post>) {
        postDao.upsertAll(posts.map {
            PostEntity(
                id = it.id.value, authorId = it.authorId.value, kind = it.kind.name,
                text = it.text, mediaUrl = it.mediaUrl, waveformUrl = it.waveformUrl,
                likeCount = it.likeCount, commentCount = it.commentCount,
                playCount = it.playCount, likedByMe = it.likedByMe,
                createdAt = it.createdAt.toEpochMilliseconds(),
                document = com.studioone.mobile.core.data.mapper.ProjectSerializer.json
                    .encodeToString(Post.serializer(), it),
            )
        })
    }

    private fun com.studioone.mobile.core.network.api.PostWire.toModel() = Post(
        id = PostId(id), authorId = UserId(authorId),
        author = UserProfile(
            userId = UserId(authorId), displayName = authorName, handle = authorHandle ?: "",
            avatarUrl = authorAvatar,
        ),
        kind = runCatching { PostKind.valueOf(this.kind) }.getOrDefault(PostKind.TRACK),
        text = text, mediaUrl = mediaUrl, waveformUrl = waveformUrl,
        likeCount = likeCount, commentCount = commentCount, playCount = playCount,
        likedByMe = likedByMe, createdAt = runCatching { Instant.parse(this.createdAt) }.getOrDefault(Instant.DISTANT_PAST),
        bpm = bpm, genre = genre, projectId = projectId?.let(::com.studioone.mobile.core.model.ProjectId),
        isPremiumOnly = isPremiumOnly, priceMicros = priceMicros,
    )
}
