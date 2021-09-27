package org.grapheneos.apps.client.ui.mainScreen

import android.content.Context
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

@AndroidEntryPoint
class MainScreen : Fragment() {

    private lateinit var binding: MainScreenBinding
    lateinit var appsViewModel: App

    override fun onAttach(context: Context) {
        super.onAttach(context)
        appsViewModel = context.applicationContext as App
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

        appsViewModel.refreshMetadata {
            showSnackbar(it.genericMsg)
        }

        appsViewModel.liveInstallablePackageInfo().observe(
            viewLifecycleOwner
        ) { packageInfo ->
            binding.syncing.isVisible = packageInfo.isNullOrEmpty()
            appsListAdapter.submitList(packageInfo)
        }

    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(
            binding.root,
            msg,
            Snackbar.LENGTH_SHORT
        ).show()
    }

}