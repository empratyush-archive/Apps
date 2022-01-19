package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
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

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_menu, menu)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setHasOptionsMenu(true)
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

        ViewCompat.setOnApplyWindowInsetsListener(
            binding.appsRecyclerView
        ) { v, insets ->

            val paddingInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.mandatorySystemGestures() or
                        WindowInsetsCompat.Type.displayCutout()
            )

            binding.appsRecyclerView.updatePadding(
                bottom = paddingInsets.bottom
            )
            insets
        }

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { newValue ->
            runOnUiThread {
                val packagesInfoMap = newValue ?: return@runOnUiThread
                val sent = packagesInfoMap.toInstall()
                updateUi(isSyncing = false, packagesInfoMap.isNullOrEmpty())
                appsListAdapter.submitList(sent)
            }
        }

        binding.retrySync.setOnClickListener { refresh() }
        refresh()
    }

    private fun Map<String, PackageInfo>.toInstall(): List<InstallablePackageInfo> {
        val result = mutableListOf<InstallablePackageInfo>()
        val value = this.values
        for (item in value) {
            result.add(InstallablePackageInfo(item.id, item))
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
            showSnackbar(
                it.genericMsg + if (it.error != null) "\n${it.error.localizedMessage}" else "",
                !it.isSuccessFull
            )
        }
    }

    private fun updateUi(isSyncing: Boolean = true, canRetry: Boolean = false) {
        runOnUiThread {
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