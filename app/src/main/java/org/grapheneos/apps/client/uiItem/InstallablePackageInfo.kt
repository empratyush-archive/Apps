package org.grapheneos.apps.client.uiItem

import androidx.recyclerview.widget.DiffUtil

data class InstallablePackageInfo(
    val name: String,
    val installStatus: InstallStatus
) {

    open class UiItemDiff : DiffUtil.ItemCallback<InstallablePackageInfo>() {

        override fun areItemsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ) = oldItem.name == newItem.name

        override fun areContentsTheSame(
            oldItem: InstallablePackageInfo,
            newItem: InstallablePackageInfo
        ) : Boolean{

          return  oldItem == newItem
        }
    }

}