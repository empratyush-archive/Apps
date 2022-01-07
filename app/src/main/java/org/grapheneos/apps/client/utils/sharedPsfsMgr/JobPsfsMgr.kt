package org.grapheneos.apps.client.utils.sharedPsfsMgr

import android.content.Context
import android.content.Context.MODE_PRIVATE
class JobPsfsMgr(val context: Context ) {

    private val sharedPsfs = context.getSharedPreferences(AUTO_UPDATE_PREFERENCE, MODE_PRIVATE)

    companion object{
        private const val AUTO_UPDATE_PREFERENCE = "seamlessUpdate"
        private const val AUTO_UPDATE_KEY = "seamlessUpdateEnabled"
    }

    fun isEnabled() = sharedPsfs.getBoolean(AUTO_UPDATE_KEY, false)

    fun onPsfsChanged(listener : (isEnabled : Boolean) -> Unit){
        listener.invoke(isEnabled() )
        sharedPsfs.registerOnSharedPreferenceChangeListener { sf, key ->
            if(key == AUTO_UPDATE_KEY){
                listener.invoke(sf.getBoolean(AUTO_UPDATE_KEY, false))
            }
        }
    }
}