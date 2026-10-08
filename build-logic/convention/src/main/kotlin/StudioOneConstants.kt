package com.studioone.buildlogic

/** Shared build constants for all StudioOne modules. */
object StudioOne {
    const val COMPILE_SDK = 35
    const val TARGET_SDK = 35
    const val MIN_SDK = 26

    const val APP_ID = "com.studioone.mobile"
    const val VERSION_CODE = 1
    const val VERSION_NAME = "0.1.0"

    /** Namespace prefix; each module appends its own path, e.g. core.audio. */
    const val NAMESPACE_PREFIX = "com.studioone.mobile"
}
