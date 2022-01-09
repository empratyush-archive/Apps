package org.grapheneos.apps.client.utils.sharedPsfsMgr

import android.content.Context
import android.content.Context.MODE_PRIVATE
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class JobPsfsMgr(val context: Context) {

    companion object {
        val AUTO_UPDATE_PREFERENCE = App.getString(R.string.autoUpdatePreferenceKey)
        val AUTO_UPDATE_KEY = App.getString(R.string.seamlessUpdateEnabled)
    }

    private val sharedPsfs = context.getSharedPreferences(AUTO_UPDATE_PREFERENCE, MODE_PRIVATE)

    private fun isAutoUpdateEnabled() = sharedPsfs.getBoolean(
        AUTO_UPDATE_KEY,
        context.resources.getBoolean(R.bool.auto_update_default)
    )

    fun onAutoUpdatePsfsChanged(listener: (isEnabled: Boolean) -> Unit) {
        listener.invoke(isAutoUpdateEnabled())
        sharedPsfs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == AUTO_UPDATE_KEY) {
                listener.invoke(isAutoUpdateEnabled())
            }
        }
    }
}