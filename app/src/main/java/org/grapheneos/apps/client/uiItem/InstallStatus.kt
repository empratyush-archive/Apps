package org.grapheneos.apps.client.uiItem

sealed class InstallStatus(
    val status: String,
    val installedV: String = "N/A",
    val latestV: String
) {
    companion object {
        private fun Long?.toApkVersion(): String {
            return if (this == null || this <= 0) "N/A" else this.toString()
        }
    }

    data class Installable(
        val latestVersion: Long,
        val isClicked: Boolean = false
    ) : InstallStatus("", latestV = latestVersion.toString())

    data class Failed(
        val errorMsg: String,
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        "", latestV = latestVersion.toString(), installedV = installedVersion.toApkVersion()
    )

    data class Installed(
        val isInstalled: Boolean,
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        "",
        latestV = latestVersion.toString(),
        installedV = installedVersion.toApkVersion()
    )

    data class Updatable(
        val installedVersion: Long,
        val latestVersion: Long
    ) : InstallStatus(
        "",
        latestV = latestVersion.toString(),
        installedV = installedVersion.toApkVersion()
    )

    data class Downloading(
        val isInstalled: Boolean,
        val installedVersion: Long?,
        val latestVersion: Long,
        val downloadSize: Int, //KB
        val downloadedSize: Int, //KB
        val downloadedPercent: Int,
        val completed: Boolean
    ) : InstallStatus(
        "",
        latestV = latestVersion.toString(),
        installedV = installedVersion.toApkVersion()
    )

    data class Installing(
        val isInstalling: Boolean,
        val latestVersion: Long,
        val canCancelTask: Boolean
    ) : InstallStatus("", latestV = latestVersion.toString())

    data class Updated(
        val installedVersion: Long,
        val latestVersion: Long,
    ) : InstallStatus(
        "",
        installedVersion.toApkVersion(),
        latestVersion.toString()
    )

    data class Uninstalling(
        val isUninstalling: Boolean,
        val installedVersion: Long,
        val latestVersion: Long,
        val canCancelTask: Boolean = false
    ) : InstallStatus("", latestV = latestVersion.toString())

    data class ReinstallRequired(
        val installedVersion: Long,
        val latestVersion: Long
    ): InstallStatus("", latestV = latestVersion.toString())
}
