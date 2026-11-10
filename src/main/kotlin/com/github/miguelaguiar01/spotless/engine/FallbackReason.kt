package com.github.miguelaguiar01.spotless.engine

/** Stable codes for "why the native engine could not be used"; always paired with a human detail. */
object FallbackReason {
    const val NO_MAVEN_PROJECT = "NO_MAVEN_PROJECT"
    const val NO_SPOTLESS_PLUGIN_FOUND = "NO_SPOTLESS_PLUGIN_FOUND"
    const val SPOTLESS_VERSION_UNSUPPORTED = "SPOTLESS_VERSION_UNSUPPORTED"
    const val EXTRACTION_FAILED = "EXTRACTION_FAILED"
    const val WORKER_LOAD_FAILED = "WORKER_LOAD_FAILED"
    const val WORKER_UNAVAILABLE = "WORKER_UNAVAILABLE"
    const val NO_SUITABLE_JDK = "NO_SUITABLE_JDK"
    const val GRADLE_STEP_NOT_SERIALIZABLE = "GRADLE_STEP_NOT_SERIALIZABLE"
    const val RATCHET_FROM_UNSUPPORTED = "RATCHET_FROM_UNSUPPORTED"
    const val NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    const val USER_CHOICE = "USER_CHOICE"
}

/** Outcome of config extraction for a file. */
sealed interface ConfigLookup {
    data class Found(val config: com.github.miguelaguiar01.spotless.protocol.SpotlessConfig, val warnings: List<String>) : ConfigLookup
    data class Unavailable(val reason: String, val detail: String) : ConfigLookup
}
