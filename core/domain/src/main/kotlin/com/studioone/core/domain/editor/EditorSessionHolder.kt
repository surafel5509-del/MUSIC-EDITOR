package com.studioone.core.domain.editor

/**
 * Process-wide holder for the currently open [EditorSession]. Feature tabs
 * (mixer, piano roll, instruments) resolve the session through this holder
 * instead of owning navigation-scoped state; the editor screen installs it
 * when a project opens. Kept dependency-free so feature modules do not need
 * to depend on each other.
 */
class EditorSessionHolder {
    @Volatile
    var session: EditorSession? = null
}
