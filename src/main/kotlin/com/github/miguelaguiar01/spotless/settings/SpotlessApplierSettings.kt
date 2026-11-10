package com.github.miguelaguiar01.spotless.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

enum class Engine {
    /** Worker JVM running the build's own Spotless (falls back to the build tool when needed). */
    NATIVE,
    /** Always run `mvn spotless:apply` / `gradle spotlessApply`. */
    BUILD_TOOL,
}

/** Application-level settings. The UI lives in Phase 4; the state is complete already. */
@Service
@State(name = "SpotlessApplierNativeSettings", storages = [Storage("spotless-applier-native.xml")])
class SpotlessApplierSettings : PersistentStateComponent<SpotlessApplierSettings> {

    @JvmField var engine: Engine = Engine.NATIVE

    /** Absolute JDK home used for the worker; empty = auto (project JDK, then the IDE runtime). */
    @JvmField var workerJdkHome: String = ""

    /** Worker exits after this many minutes without requests; 0 keeps it alive. */
    @JvmField var workerIdleTimeoutMinutes: Int = 15

    @JvmField var workerMaxHeapMb: Int = 1024

    /** Start the worker and load configs when a project opens. */
    @JvmField var prewarmOnProjectOpen: Boolean = false

    /** Show a notification (once per project) explaining why the build tool was used instead. */
    @JvmField var showFallbackReasons: Boolean = true

    // Carried over from the original plugin.
    @JvmField var optimizeImportsBeforeApplying: Boolean = false
    @JvmField var prohibitImportsWithAsterisk: Boolean = false
    @JvmField var prohibitGradleCache: Boolean = false

    override fun getState(): SpotlessApplierSettings = this

    override fun loadState(state: SpotlessApplierSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        @JvmStatic
        fun getInstance(): SpotlessApplierSettings =
            ApplicationManager.getApplication().getService(SpotlessApplierSettings::class.java)
    }
}
