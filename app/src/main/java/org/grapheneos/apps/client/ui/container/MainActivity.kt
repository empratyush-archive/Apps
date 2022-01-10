package org.grapheneos.apps.client.ui.container

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.service.SeamlessUpdaterJob

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setDecorFitsSystemWindows(false)

        if (SeamlessUpdaterJob.NOTIFICATION_ACTION == intent.action) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(
                SeamlessUpdaterJob.NOTIFICATION_ID
            )
        }
    }
}