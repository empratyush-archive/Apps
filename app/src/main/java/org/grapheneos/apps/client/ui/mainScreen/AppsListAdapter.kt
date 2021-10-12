package org.grapheneos.apps.client.ui.mainScreen

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.ItemAppsBinding
import org.grapheneos.apps.client.uiItem.InstallStatus
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo

class AppsListAdapter(private val onItemClick: (packageName: String) -> Unit) :
    ListAdapter<InstallablePackageInfo, AppsListAdapter.AppsListViewHolder>(
        InstallablePackageInfo.UiItemDiff()
    ) {

    inner class AppsListViewHolder(
        private val binding: ItemAppsBinding,
        private val onItemClick: (packageName: String) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(position: Int) {
            val currentItem = currentList[position]
            val installStatus = currentItem.installStatus

            binding.appName.text = currentItem.name
            binding.install.text = currentItem.installStatus.status
            binding.latestVersion.text = installStatus.latestV
            binding.installedVersion.text = installStatus.installedV

            binding.install.setOnClickListener {
                onItemClick.invoke(currentItem.name)
            }

            binding.apply {
                install.isEnabled = true
                downloadProgress.isInvisible = true
                when (installStatus) {
                    is InstallStatus.Installed -> {
                        install.text = App.getString(R.string.uninstall)
                        downloadProgress.isInvisible = true
                    }
                    is InstallStatus.Installing -> {
                        install.text = App.getString(R.string.installing)
                        install.isEnabled = installStatus.canCancelTask
                    }
                    is InstallStatus.Uninstalling -> {
                        install.text = App.getString(R.string.uninstalling)
                        install.isEnabled = false
                        downloadProgress.isInvisible = true
                    }
                    is InstallStatus.Downloading -> {
                        install.text = App.getString(R.string.downloading)
                        downloadProgress.setProgressCompat(installStatus.downloadedPercent, false)
                        downloadProgress.isInvisible = false
                    }
                    is InstallStatus.Installable -> {
                        install.text = App.getString(R.string.install)
                    }
                    is InstallStatus.Updated -> {
                        install.text = App.getString(R.string.updated)
                    }
                    is InstallStatus.Failed -> {
                        install.text = App.getString(R.string.failedRetry)
                    }
                    is InstallStatus.Updatable -> {
                        install.text = App.getString(R.string.update)
                    }
                    is InstallStatus.ReinstallRequired -> {
                        install.text = App.getString(R.string.reinstallRequired)
                    }
                }
            }
        }

    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = AppsListViewHolder(
        ItemAppsBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        ),
        onItemClick
    )

    override fun onBindViewHolder(holder: AppsListViewHolder, position: Int) = holder.bind(position)

    override fun getItemCount() = currentList.size

}
