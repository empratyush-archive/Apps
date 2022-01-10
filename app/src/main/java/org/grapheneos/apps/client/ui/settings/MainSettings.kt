package org.grapheneos.apps.client.ui.settings

import android.os.Bundle
import android.view.View
import android.view.ViewGroup

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class MainSettings : PreferenceFragmentCompat() {

    companion object {
        private val CHECK_FOR_UPDATES_NOW_KEY = App.getString(R.string.checkForUpdatesNow)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = JobPsfsMgr.AUTO_UPDATE_PREFERENCE
        addPreferencesFromResource(R.xml.settings)

        findPreference<Preference>(CHECK_FOR_UPDATES_NOW_KEY)?.onPreferenceClickListener =
            Preference.OnPreferenceClickListener { preference: Preference? ->
                preference ?: return@OnPreferenceClickListener false
                (requireContext().applicationContext as App).downloadUpdatableAppsNow()
                true
            }

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.setOnApplyWindowInsetsListener { v, insets ->
            val paddingInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.mandatorySystemGestures() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = paddingInsets.top
            }
            insets
        }
    }
}