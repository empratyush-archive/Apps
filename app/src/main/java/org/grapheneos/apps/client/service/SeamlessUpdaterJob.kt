package org.grapheneos.apps.client.service

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

class SeamlessUpdaterJob : JobService() {

    private fun List<String>.valuesAsString(): String {
        var result = ""
        val isMultiple = size > 1
        for (i in 0 until size) {
            result += if (i != (size - 1) && isMultiple) {
                "${get(i)}, "
            } else {
                "${get(i)} "
            }
        }
        return result
    }

    override fun onStartJob(params: JobParameters?): Boolean {

        val app = (this as Context).applicationContext as App
        app.seamlesslyUpdateApps { result ->

            if (result.executedSuccessfully) {
                val updated = result.updatedSuccessfully.valuesAsString()
                val failed = result.failedToUpdate.valuesAsString()

                var content = ""
                if (updated.isNotBlank() && result.updatedSuccessfully.isNotEmpty()) {
                    content += "$updated has been successfully updated "
                }

                if (failed.isNotBlank() && result.failedToUpdate.isNotEmpty()) {
                    if (content.isNotBlank()) content += ", "
                    content += "$failed has failed to update."
                }

                val notificationManager =
                    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                val notification = Notification.Builder(this, App.BACKGROUND_SERVICE_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Seamless update result")
                    .setContentText(content)
                    .setOnlyAlertOnce(true)
                    .build()

                notificationManager.notify(11, notification)
            }

            jobFinished(params, true)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true
    }
}