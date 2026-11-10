package com.github.miguelaguiar01.spotless.notify

import com.github.miguelaguiar01.spotless.settings.SpotlessApplierSettings
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.util.concurrent.ConcurrentHashMap

/** Quiet successes (status bar), clear errors (balloons), fallback reasons once per project. */
@Service(Service.Level.PROJECT)
class SpotlessNotifications(private val project: Project) {
    private val group get() = NotificationGroupManager.getInstance().getNotificationGroup(GROUP_ID)
    private val reportedFallbacks = ConcurrentHashMap.newKeySet<String>()

    fun statusBar(message: String) {
        ApplicationManager.getApplication().invokeLater({
            WindowManager.getInstance().getStatusBar(project)?.info = message
        }, project.disposed)
    }

    fun info(title: String, content: String) {
        group.createNotification(title, content, NotificationType.INFORMATION).notify(project)
    }

    fun warning(title: String, content: String) {
        group.createNotification(title, content, NotificationType.WARNING).notify(project)
    }

    fun error(title: String, content: String) {
        group.createNotification(title, content, NotificationType.ERROR).notify(project)
    }

    /** Explains, once per project and reason, why the build tool was used instead of the native engine. */
    fun fallback(reason: String, detail: String) {
        LOG.info("Falling back to the build tool: $reason — $detail")
        if (!SpotlessApplierSettings.getInstance().showFallbackReasons) return
        if (!reportedFallbacks.add(reason)) return
        group.createNotification(
            "Spotless: using the build tool instead of the native engine",
            "$detail (reason: $reason)",
            NotificationType.INFORMATION,
        ).addAction(NotificationAction.createSimpleExpiring("Don't show fallback reasons") {
            SpotlessApplierSettings.getInstance().showFallbackReasons = false
        }).notify(project)
    }

    fun resetFallbackReports() = reportedFallbacks.clear()

    companion object {
        const val GROUP_ID = "Spotless Applier Native"
        private val LOG = logger<SpotlessNotifications>()
        fun getInstance(project: Project): SpotlessNotifications = project.getService(SpotlessNotifications::class.java)
    }
}
