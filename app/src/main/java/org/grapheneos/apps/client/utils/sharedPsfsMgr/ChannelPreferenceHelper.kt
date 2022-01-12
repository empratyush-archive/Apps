package org.grapheneos.apps.client.utils.sharedPsfsMgr

import android.content.Context
import android.content.SharedPreferences

import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.App

object ChannelPreferenceHelper {
    val defaultChannel = App.getString(R.string.channel_default)

    private fun getAppChannelPreferences(context: Context): SharedPreferences {
        return context.applicationContext
            .getSharedPreferences("app_channel", Context.MODE_PRIVATE)
    }

    fun getPackageChannel(context: Context, pkgName: String): String {
        return getAppChannelPreferences(context).getString(pkgName, defaultChannel)!!
    }
}