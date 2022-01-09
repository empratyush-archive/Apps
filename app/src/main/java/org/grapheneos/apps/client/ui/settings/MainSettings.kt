package org.grapheneos.apps.client.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.ViewGroup

import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.preference.PreferenceFragmentCompat
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr

class MainSettings : PreferenceFragmentCompat() {

    companion object {
        private val AUTO_UPDATE_INTERVAL_KEY = App.getString(R.string.seamlessUpdateInterval)
        private val AUTO_INSTALL_UPDATES_KEY = App.getString(R.string.seamlessUpdateEnabled)
        private val CHECK_FOR_UPDATES_NOW_KEY = App.getString(R.string.checkForUpdatesNow)

        private val AUTO_UPDATE_INTERVAL_DEFAULT = App.getString(R.string.auto_download_interval_default)

        private fun getPreferences(context: Context): SharedPreferences {
            return context.getSharedPreferences(JobPsfsMgr.AUTO_UPDATE_PREFERENCE, Context.MODE_PRIVATE)
        }

        fun getAutoUpdateDownloadedApps(context: Context) = getPreferences(context).getBoolean(
            AUTO_INSTALL_UPDATES_KEY, context.resources.getBoolean(R.bool.auto_update_default))

        fun getAutoUpdateInterval(context: Context): Long = (getPreferences(context).getString(
            AUTO_UPDATE_INTERVAL_KEY, AUTO_UPDATE_INTERVAL_DEFAULT) ?:
            AUTO_UPDATE_INTERVAL_DEFAULT).toLong()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = JobPsfsMgr.AUTO_UPDATE_PREFERENCE
        addPreferencesFromResource(R.xml.settings)
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