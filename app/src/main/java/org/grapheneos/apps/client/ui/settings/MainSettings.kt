package org.grapheneos.apps.client.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat

import org.grapheneos.apps.client.R

class MainSettings: PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        val autoDownloadPreference =
            preferenceManager.findPreference<SwitchPreferenceCompat>(KEY_AUTO_DOWNLOAD)
        val autoUpdatePreference =
            preferenceManager.findPreference<SwitchPreferenceCompat>(KEY_AUTO_UPDATE)

        val booleanChangeListener =
            Preference.OnPreferenceChangeListener { preference: Preference?, newValue: Any ->
                if (preference == null || preference.key == null) {
                    return@OnPreferenceChangeListener false
                }
                val value: Boolean = newValue as Boolean
                when (preference.key) {
                    KEY_AUTO_UPDATE -> {
                        getPreferences(requireContext()).edit()
                            .putBoolean(preference.key, value).apply()
                    }
                    KEY_AUTO_DOWNLOAD -> {
                        if (value) {
                            autoUpdatePreference?.isChecked = value
                        }
                        getPreferences(requireContext()).edit()
                            .putBoolean(preference.key, value).apply()
                    }
                    else -> return@OnPreferenceChangeListener false
                }
                true
            }

        autoUpdatePreference?.onPreferenceChangeListener = booleanChangeListener
        autoDownloadPreference?.onPreferenceChangeListener = booleanChangeListener

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

    companion object {
        private const val KEY_AUTO_UPDATE = "autoUpdate"
        private const val KEY_AUTO_DOWNLOAD = "autoDownload"

        private fun getPreferences(context: Context): SharedPreferences {
            val appContext = context.applicationContext
            return PreferenceManager.getDefaultSharedPreferences(appContext)
        }

        fun getAutoDownload(context: Context): Boolean {
            val defaultAutoDownload = (context.getString(R.string.autodownload_default)).toBoolean()
            return getPreferences(context).getBoolean(KEY_AUTO_DOWNLOAD, defaultAutoDownload)
        }

        fun getAutoUpdate(context: Context): Boolean {
            val defaultAutoUpdate = (context.getString(R.string.autoupdate_default)).toBoolean()
            return getPreferences(context).getBoolean(KEY_AUTO_UPDATE, defaultAutoUpdate)
        }
    }
}