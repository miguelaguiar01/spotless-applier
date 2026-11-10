package com.github.miguelaguiar01.spotless.ui

import com.github.miguelaguiar01.spotless.settings.Engine
import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty

/** Settings | Tools | Spotless Applier Native. */
class SpotlessSettingsConfigurable : BoundConfigurable("Spotless Applier Native") {

    override fun createPanel(): DialogPanel {
        val s = SpotlessApplierSettings.getInstance()
        return panel {
            group("Engine") {
                row("Engine:") {
                    comboBox(listOf(Engine.NATIVE, Engine.BUILD_TOOL), renderer = com.intellij.ui.SimpleListCellRenderer.create("") {
                        when (it) {
                            Engine.NATIVE -> "Native (recommended)"
                            Engine.BUILD_TOOL -> "Build tool (mvn / gradle)"
                            null -> ""
                        }
                    }).bindItem(s::engine.toNullableProperty())
                }
                row {
                    checkBox("Show why the build tool was used instead of the native engine (once per project)")
                        .bindSelected(s::showFallbackReasons)
                }
                row {
                    checkBox("Start the worker and load Spotless configurations when a project opens")
                        .bindSelected(s::prewarmOnProjectOpen)
                }
                row {
                    checkBox("Also run Spotless from Code | Reformat Code (experimental)")
                        .bindSelected(s::integrateWithReformatCode)
                        .comment("Registers Spotless as the formatter for files covered by the Spotless configuration.")
                }
            }
            group("Worker JVM") {
                row("JDK home:") {
                    textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select JDK Home"))
                        .bindText(s::workerJdkHome)
                        .columns(40)
                        .comment("Leave empty to use the Maven runner JRE, the Gradle JVM, the project SDK or the IDE runtime (in that order). Java 17+ is required for Spotless 3.x.")
                }
                row("Idle timeout (minutes):") {
                    intTextField(0..1440).bindIntText(s::workerIdleTimeoutMinutes).comment("0 keeps the worker alive until the project is closed.")
                }
                row("Max heap (MB):") {
                    intTextField(256..16384).bindIntText(s::workerMaxHeapMb)
                }
            }
            group("Before Applying") {
                lateinit var optimize: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
                row {
                    optimize = checkBox("Optimize imports before applying").bindSelected(s::optimizeImportsBeforeApplying)
                }
                indent {
                    row {
                        checkBox("Prohibit imports with asterisk '*'").bindSelected(s::prohibitImportsWithAsterisk)
                            .enabledIf(optimize.selected)
                    }
                }
            }
            group("Build Tool Fallback") {
                row {
                    checkBox("Prohibit Gradle configuration cache for 'spotlessApply'").bindSelected(s::prohibitGradleCache)
                }
            }
        }
    }
}
