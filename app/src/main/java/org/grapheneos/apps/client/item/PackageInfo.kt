package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App

/**
 * This data class hold everything about a package name including
 * Package name, active session id {@link PackageInstaller.EXTRA_SESSION_ID} ,
 * Installed info (like required installation or update available etc)
 * Downloading/Installing Info {@link TaskInfo}
 * @property id the package name as Id.
 * @author empratyush
 * */
data class PackageInfo(
    val id: String,
    val sessionInfo: SessionInfo,
    val packageVariant: PackageVariant,
    val taskInfo: TaskInfo = TaskInfo(-1, "", App.DOWNLOAD_TASK_FINISHED),
    val downloadStatus: DownloadStatus? = null,
    val installStatus: InstallStatus
) {

    fun withUpdatedInstallStatus(newStatus: InstallStatus): PackageInfo =
        PackageInfo(
            id, sessionInfo, packageVariant, taskInfo,
            downloadStatus, newStatus
        )

    fun withUpdatedDownloadStatus(newStatus: DownloadStatus?): PackageInfo =
        PackageInfo(
            id, sessionInfo, packageVariant, taskInfo,
            newStatus, installStatus
        )

    fun withUpdatedSession(newSessionInfo: SessionInfo): PackageInfo =
        PackageInfo(
            id, newSessionInfo, packageVariant, taskInfo,
            downloadStatus, installStatus
        )

    fun withUpdatedTask(newTaskInfo: TaskInfo): PackageInfo =
        PackageInfo(
            id, sessionInfo, packageVariant, newTaskInfo,
            downloadStatus, installStatus
        )

}