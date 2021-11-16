package org.grapheneos.apps.client.ui.mainScreen

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.databinding.MainScreenBinding
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

        val appsListAdapter = AppsListAdapter { packageName ->
            appsViewModel.handleOnClick(packageName) { msg ->
                showSnackbar(msg)
            }
        }
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

            v.updatePadding(
                paddingInsets.left,
                paddingInsets.top,
                paddingInsets.right,
                paddingInsets.bottom,
            )

            insets
        }

        appsViewModel.packageLiveData.observe(
            viewLifecycleOwner
        ) { packagesInfoMap ->
            runOnUiThread {
                updateUi(packagesInfoMap.isNullOrEmpty())
                appsListAdapter.submitList(packagesInfoMap.map {
                    InstallablePackageInfo(it.key, it.value)
                })
            }
        }

        binding.retrySync.setOnClickListener { refresh() }
        refresh()
    }

    private fun runOnUiThread(action: Runnable) {
        activity?.runOnUiThread(action)
    }

    private fun refresh() {
        appsViewModel.refreshMetadata {
            updateUi(!it.isSuccessFull)
            showSnackbar(it.genericMsg, !it.isSuccessFull)
        }
    }

    private fun updateUi(canRetry: Boolean = false) {
        runOnUiThread {
            binding.syncing.isVisible = false
            binding.retrySync.isVisible = canRetry
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