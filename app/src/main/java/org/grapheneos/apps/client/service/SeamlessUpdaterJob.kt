package org.grapheneos.apps.client.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import org.grapheneos.apps.client.App

class SeamlessUpdaterJob : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {

        val app = (this as Context).applicationContext as App
        app.seamlesslyUpdateApps {
            jobFinished(params, true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}