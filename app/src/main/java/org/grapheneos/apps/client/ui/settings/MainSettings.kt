package org.grapheneos.apps.client.ui.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class MainSettings : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = JobPsfsMgr.AUTO_UPDATE_PREFERENCE
        addPreferencesFromResource(R.xml.settings)
        preferenceManager.findPreference<SwitchPreferenceCompat>(JobPsfsMgr.AUTO_DOWNLOAD_KEY)
            ?.setOnPreferenceChangeListener { pref: Preference, value: Any ->
                if (pref.key == JobPsfsMgr.AUTO_DOWNLOAD_KEY) {
                    requireContext().getSharedPreferences(
                        JobPsfsMgr.AUTO_UPDATE_PREFERENCE,
                        Context.MODE_PRIVATE
                    ).edit().putBoolean(JobPsfsMgr.AUTO_DOWNLOAD_KEY, value as Boolean).apply()
                    if (value) {
                        preferenceManager.findPreference<SwitchPreferenceCompat>(JobPsfsMgr
                            .AUTO_UPDATE_KEY)?.isChecked = true
                    }
                }
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