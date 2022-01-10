package org.grapheneos.apps.client.utils.sharedPsfsMgr

import android.app.job.JobInfo
import android.content.Context
import android.content.Context.MODE_PRIVATE
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class JobPsfsMgr(val context: Context) {

    companion object {
        val AUTO_UPDATE_PREFERENCE = App.getString(R.string.autoUpdatePreferenceKey)
        val AUTO_UPDATE_KEY = App.getString(R.string.seamlessUpdateEnabled)
        val NETWORK_TYPE_KEY = App.getString(R.string.networkType)
        val RESCHEDULE_TIME_KEY = App.getString(R.string.rescheduleTiming)
    }

    private val sharedPsfs = context.getSharedPreferences(AUTO_UPDATE_PREFERENCE, MODE_PRIVATE)

    private fun isAutoUpdateEnabled() = sharedPsfs.getBoolean(
        AUTO_UPDATE_KEY,
        context.resources.getBoolean(R.bool.auto_update_default)
    )

    private fun jobNetworkType(): Int {
        return when (networkType()) {
            2 -> JobInfo.NETWORK_TYPE_UNMETERED
            3 -> JobInfo.NETWORK_TYPE_NOT_ROAMING
            else -> JobInfo.NETWORK_TYPE_ANY
        }
    }

    private val defaultRescheduleTiming =
        context.resources.getString(R.string.reschedule_timing_default).toLong()

    private fun rescheduleTimingInMilli() = sharedPsfs.getString(
        RESCHEDULE_TIME_KEY,
        defaultRescheduleTiming.toString()
    )?.toLongOrNull() ?: defaultRescheduleTiming

    private fun networkType(): Int = sharedPsfs.getString(
        NETWORK_TYPE_KEY,
        context.resources.getString(R.string.network_type_default)
    )!!.toInt()

    fun onNetworkTypeChanged(listener: (type: Int) -> Unit) {
        listener.invoke(networkType())
        sharedPsfs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == NETWORK_TYPE_KEY) {
                listener.invoke(networkType())
            }
        }
    }

    fun onJobPsfsChanged(listener: (isEnabled: Boolean, networkType: Int, rescheduleTimingInMilli: Long) -> Unit) {
        listener.invoke(
            isAutoUpdateEnabled(),
            jobNetworkType(),
            rescheduleTimingInMilli()
        )
        sharedPsfs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == AUTO_UPDATE_KEY || key == NETWORK_TYPE_KEY || key == RESCHEDULE_TIME_KEY) {
                listener.invoke(
                    isAutoUpdateEnabled(),
                    jobNetworkType(),
                    rescheduleTimingInMilli()
                )
            }
        }
    }
}