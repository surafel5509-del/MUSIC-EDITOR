package com.studioone.core.common.error

/** Base exception for all domain errors; carries a machine-readable [code]. */
sealed class StudioOneException(
    override val message: String,
    override val cause: Throwable? = null,
    val code: String = "unknown",
) : Exception(message, cause) {

    class Storage(message: String, cause: Throwable? = null) :
        StudioOneException(message, cause, "storage")

    class Network(message: String, cause: Throwable? = null) :
        StudioOneException(message, cause, "network")

    class Auth(message: String, cause: Throwable? = null) :
        StudioOneException(message, cause, "auth")

    class Audio(message: String, cause: Throwable? = null) :
        StudioOneException(message, cause, "audio")

    class ProjectNotFound(val projectId: String) :
        StudioOneException("Project $projectId not found", null, "project_not_found")

    class Validation(message: String) :
        StudioOneException(message, null, "validation")

    class Unknown(message: String, cause: Throwable? = null) :
        StudioOneException(message, cause, "unknown")

    companion object {
        fun from(throwable: Throwable): StudioOneException = when (throwable) {
            is StudioOneException -> throwable
            is java.io.IOException -> Storage("I/O failure: ${throwable.message}", throwable)
            else -> Unknown(throwable.message ?: "Unknown error", throwable)
        }
    }
}
