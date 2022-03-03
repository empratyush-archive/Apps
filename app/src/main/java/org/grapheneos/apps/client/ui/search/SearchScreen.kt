package org.grapheneos.apps.client.ui.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.FragmentNavigatorExtras
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.transition.platform.MaterialElevationScale
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.databinding.SearchScreenBinding
import org.grapheneos.apps.client.ui.mainScreen.MainScreenDirections
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo.Companion.applySearchQueryFilter
import org.grapheneos.apps.client.utils.runOnUiThread
import org.grapheneos.apps.client.utils.showSnackbar
import javax.inject.Inject

@AndroidEntryPoint
class SearchScreen : Fragment() {

    @Inject
    lateinit var searchState: SearchScreenState
    private lateinit var binding: SearchScreenBinding
    private val appDataModel by lazy {
        requireContext().applicationContext as App
    }
    private val searchScreenAdapter by lazy {
        SearchItemListAdapter(this)
    }
    private var lastItems: List<InstallablePackageInfo> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        setHasOptionsMenu(true)
        binding = SearchScreenBinding.inflate(layoutInflater, container, false)
        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.search_screen_menu, menu)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, inset ->

            val paddingInsets = inset.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = paddingInsets.bottom
            }
            inset
        }

        binding.appsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = searchScreenAdapter
            itemAnimator = DefaultItemAnimator().apply {
                changeDuration = 0
            }
        }

        appDataModel.packageLiveData.observe(viewLifecycleOwner) { newValue ->
            runOnUiThread {
                val sent = InstallablePackageInfo.fromMap(newValue)
                lastItems = sent
                submit()
            }
        }

        searchState.searchQuery.observe(viewLifecycleOwner) {
            submit()
        }

    }

    private fun submit() {
        val allItems = mutableListOf<InstallablePackageInfo>().apply {
            addAll(lastItems)
        }
        val query = searchState.getCurrentQuery()
        val filterList = allItems.applySearchQueryFilter(query)

        binding.output.isVisible = false
        binding.appsRecyclerView.isVisible = true
        if (allItems.isEmpty()) {
            //sync isn't finished yet or something?
            searchScreenAdapter.submitList(emptyList())
        } else if (query.isBlank() || query.isEmpty()) {
            //search query is empty
            searchScreenAdapter.submitList(emptyList())
        } else if (filterList.isEmpty()) {
            //no match found LOL
            binding.appsRecyclerView.isVisible = false
            binding.output.isVisible = true
            searchScreenAdapter.submitList(emptyList())
        } else {
            searchScreenAdapter.submitList(filterList)
        }

    }

    fun installPackage(root: View, appName: String, pkgName: String) {
        if (!appDataModel.isDependenciesInstalled(pkgName)) {
            navigateToDetailsScreen(root, appName, pkgName, true)
        } else {
            appDataModel.handleOnClick(pkgName) { msg ->
                showSnackbar(msg)
            }
        }
    }

    fun navigateToDetailsScreen(
        root: View,
        appName: String,
        pkgName: String,
        installationRequested: Boolean = false
    ) {
        exitTransition = MaterialElevationScale(false)
        reenterTransition = MaterialElevationScale(true)
        val extra = FragmentNavigatorExtras(root to getString(R.string.detailsScreenTransition))
        findNavController().navigate(
            MainScreenDirections.actionToDetailsScreen(
                pkgName,
                appName,
                installationRequested
            ), extra
        )
    }

}