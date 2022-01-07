package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.isGone
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.MainScreenBinding
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo

@AndroidEntryPoint
class MainScreen : Fragment() {

    private lateinit var binding: MainScreenBinding
    private val appsViewModel by lazy {
        requireContext().applicationContext as App
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainScreenBinding.inflate(
            inflater,
            container,
            false
        )
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val appsListAdapter = AppsListAdapter(onInstallItemClick = { packageName ->
            appsViewModel.handleOnClick(packageName) { msg ->
                showSnackbar(msg)
            }
        }, onChannelItemClick = { packageName, channel, callback ->
            appsViewModel.handleOnVariantChange(packageName, channel, callback)
        }, onUninstallItemClick = { packageName ->
            appsViewModel.uninstallPackage(packageName) { msg ->
                showSnackbar(msg)
            }
        }, onAppInfoItemClick = { packageName ->
            appsViewModel.openAppDetails(packageName)
        })

        binding.appsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = appsListAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 0
            }
        }
        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.settings_menu -> {
                    findNavController().navigate(R.id.action_to_settings)
                    true
                }
                else -> false
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.toolbar
        ) { v, insets ->

            val paddingInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.mandatorySystemGestures() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = paddingInsets.top
            }
            binding.appsRecyclerView.updatePadding(
                bottom = paddingInsets.bottom
            )
            insets
        }

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { packagesInfoMap ->
            runOnUiThread {
                updateUi(isSyncing = false, packagesInfoMap.isNullOrEmpty())
                appsListAdapter.submitList(packagesInfoMap.toInstall())
            }
        }

        binding.retrySync.setOnClickListener { refresh() }
        refresh()
    }

    private fun Map<String, PackageInfo>.toInstall(): List<InstallablePackageInfo> {
        val result = mutableListOf<InstallablePackageInfo>()
        val value = this
        for (item in value) {
            result.add(InstallablePackageInfo(item.key, item.value))
        }
        return result
    }

    private fun runOnUiThread(action: Runnable) {
        activity?.runOnUiThread(action)
    }

    private fun refresh() {
        updateUi(isSyncing = true, canRetry = false)
        appsViewModel.refreshMetadata {
            updateUi(isSyncing = false, canRetry = !it.isSuccessFull)
            showSnackbar(it.genericMsg, !it.isSuccessFull)
            if (it.isSuccessFull) {
                appsViewModel.maybeAutoDownload { msg ->
                    showSnackbar(msg)
                }
            }
        }
    }

    private fun updateUi(isSyncing: Boolean = true, canRetry: Boolean = false) {
        runOnUiThread {
            binding.toolbar.isVisible = !isSyncing
            binding.syncing.isVisible = isSyncing
            binding.appsRecyclerView.isGone = isSyncing || canRetry
            binding.retrySync.isVisible = !isSyncing && canRetry
        }
    }

    private fun showSnackbar(msg: String, isError: Boolean? = null) {
        val snackbar = Snackbar.make(
            binding.root,
            msg,
            Snackbar.LENGTH_SHORT
        )
        snackbar.setBackgroundTint(requireContext().getColor(android.R.color.system_neutral1_700))
        snackbar.setTextColor(requireActivity().getColor(android.R.color.system_accent1_200))

        if (isError == true) {
            snackbar.setTextColor(requireActivity().getColor(android.R.color.system_accent3_200))
        }
        snackbar.show()
    }

}