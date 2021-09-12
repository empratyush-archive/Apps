package org.grapheneos.apps.client.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Observer
import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R
import org.grapheneos.apps.client.item.TaskInfo

class KeepAppActive : Service() {

    private lateinit var notificationMgr: NotificationManagerCompat
    private lateinit var app: App

    private val observer = Observer<List<TaskInfo>> { infos ->
        infos.forEach { task ->
            if (task.progress == App.DOWNLOAD_TASK_FINISHED) {
                notificationMgr.cancel(task.id)
            } else {
                val notification = Notification.Builder(this, App.BACKGROUND_SERVICE_CHANNEL)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(task.title)
                    .setOnlyAlertOnce(true)
                    .setProgress(100, task.progress, false)
                    .build()
                notification.flags = Notification.FLAG_ONGOING_EVENT
                notificationMgr.notify(task.id, notification)
            }
        }

        if (infos.isAllTaskCompleted()) stopService()
    }

    private fun List<TaskInfo>.isAllTaskCompleted() : Boolean{
        var anyPending = true
        forEach { task ->
            if(task.progress != App.DOWNLOAD_TASK_FINISHED){
                anyPending = false
            }
        }
        return anyPending
    }

    private fun stopService() {
        stopSelf()
        notificationMgr.cancelAll()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationMgr = NotificationManagerCompat.from(this)
        app = applicationContext as App
        app.updateServiceStatus(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.getTasksInfo().observeForever(observer)
        val notification = Notification.Builder(this, App.BACKGROUND_SERVICE_CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("few packages is being downloaded")
            .setOnlyAlertOnce(true)
            .build()
        notification.flags = Notification.FLAG_FOREGROUND_SERVICE
        startForeground(1, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        app.getTasksInfo().removeObserver(observer)
        app.updateServiceStatus(false)
    }

}