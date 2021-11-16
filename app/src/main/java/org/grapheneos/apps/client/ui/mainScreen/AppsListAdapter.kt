package org.grapheneos.apps.client.ui.mainScreen

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.grapheneos.apps.client.databinding.ItemAppsBinding
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import kotlin.math.roundToInt

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
            val info = currentItem.packageInfo
            val installStatus = info.installStatus
            val downloadStatus = info.downloadStatus

            binding.apply {
                appName.text = currentItem.name
                latestVersion.text = installStatus.latestV
                installedVersion.text = installStatus.installedV
            }

            if (downloadStatus != null) {
                binding.apply {
                    install.text = downloadStatus.status
                    install.isEnabled = !downloadStatus.isDownloading
                    downloadSizeInfo.isGone = !downloadStatus.isDownloading
                    downloadProgress.isInvisible = !downloadStatus.isDownloading

                    if (downloadStatus is DownloadStatus.Downloading) {
                        val progress = downloadStatus.downloadedPercent.roundToInt()
                        val sizeInfo = "${downloadStatus.downloadedSize.toMB()} MB out of " +
                                "${downloadStatus.downloadSize.toMB()} MB," +
                                "  $progress %"
                        downloadProgress.isIndeterminate =
                            (downloadStatus.downloadSize == 0) && (downloadStatus.downloadedSize == 0)
                        downloadProgress.max = 100
                        downloadProgress.setProgressCompat(
                            downloadStatus.downloadedPercent.roundToInt(),
                            true)
                        downloadSizeInfo.text = sizeInfo
                    }
                }
            } else {
                binding.apply {
                    install.text = installStatus.status
                    if (installStatus is InstallStatus.Installing || installStatus is InstallStatus.Uninstalling) {
                        downloadProgress.isInvisible = false
                        downloadProgress.isIndeterminate = true
                        downloadSizeInfo.isGone = true
                        install.isEnabled = false
                    } else {
                        install.isEnabled = true
                        downloadProgress.isInvisible = true
                        downloadSizeInfo.isGone = true
                    }
                }
            }

            binding.install.setOnClickListener {
                onItemClick.invoke(currentItem.name)
            }
        }

    }

    private fun Int.toMB(): String = String.format("%.3f", (this / 1024.0 / 1024.0))

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
