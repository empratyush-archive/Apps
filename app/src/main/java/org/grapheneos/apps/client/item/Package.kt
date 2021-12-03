package org.grapheneos.apps.client.item

data class Package(
    val appName : String,
    val packageName: String,
    val variants: List<PackageVariant>,
)