package org.grapheneos.apps.client.item

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
    val taskInfo: TaskInfo
)