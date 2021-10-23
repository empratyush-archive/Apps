package org.grapheneos.apps.client

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.item.*
import org.grapheneos.apps.client.service.KeepAppActive
import org.grapheneos.apps.client.uiItem.InstallStatus
import org.grapheneos.apps.client.uiItem.InstallablePackageInfo
import org.grapheneos.apps.client.utils.PackageManagerHelper
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.net.ssl.SSLHandshakeException

@HiltAndroidApp
class App : Application() {

    companion object {
        const val BACKGROUND_SERVICE_CHANNEL = "backgroundTask"
        const val DOWNLOAD_TASK_FINISHED = 1000
        private lateinit var context: WeakReference<Context>

        fun getString(@StringRes id: Int): String {
            return context.get()!!.getString(id)
        }
    }

    @Inject
    lateinit var metaDataHelper: MetaDataHelper

    @Inject
    lateinit var apkDownloadHelper: ApkDownloadHelper
    private val executor = Executors.newSingleThreadExecutor()

    private val sessionNdApps = mutableMapOf<Int, String>()
    private val apps = mutableMapOf<String, InstallStatus>()
    private val metadata = MutableLiveData<MetaData>()
    private val installablePackageInfo = MutableLiveData<List<InstallablePackageInfo>>()
    private val conformationAwaitedPackages = mutableMapOf<String, List<File>>()
    private val tasksInfo = MutableLiveData<Map<Int, TaskInfo>>()

    private var isActivityRunning: Activity? = null
    private var isServiceRunning = false

    private val scopeApkDownload = Dispatchers.IO + Job()
    private val scopeMetadataRefresh = Dispatchers.IO + Job()

    private val appsChangesReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val action = intent?.action ?: return
            val packageName = intent.data?.schemeSpecificPart ?: return
            val latestVersion = apps[packageName]?.latestV?.toLong() ?: 0L

            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    apps[packageName] = InstallStatus.Installed(
                        true,
                        getInstalledAppVersionCode(packageName),
                        latestVersion
                    )
                    sessionNdApps.remove(sessionNdApps.findKeyFor(packageName))
                    updateInstalledAppInfo(packageName)
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    apps[packageName] = InstallStatus.Updated(
                        getInstalledAppVersionCode(packageName),
                        latestVersion
                    )
                    updateInstalledAppInfo(packageName)
                }

                Intent.ACTION_PACKAGE_FULLY_REMOVED,
                Intent.ACTION_PACKAGE_REMOVED -> {
                    apps[packageName] = InstallStatus.Installable(
                        latestVersion
                    )
                    updateInstalledAppInfo(packageName)
                }
            }
        }
    }

    fun installIntentResponse(sessionId: Int, errorMsg: String, userDeclined: Boolean = false) {
        val packageName = sessionNdApps[sessionId] ?: return
        val metadata = metadata.forPackage(packageName) ?: return

        if (userDeclined) {
            updatePackageInfoInfo(packageName)
            updateInstalledAppInfo()
        } else {
            apps[packageName] = InstallStatus.Failed(
                errorMsg,
                getInstalledAppVersionCode(packageName),
                metadata.versionCode.toLong()
            )
            updateInstalledAppInfo(packageName)
        }
    }

    private fun MutableMap<Int, String>.findKeyFor(pkgName: String): Int {
        var result = 0
        forEach { (sessionId, packageName) ->
            if (pkgName == packageName) {
                result = sessionId
            }
        }
        return result
    }

    private fun MutableLiveData<MetaData>.forPackage(packageName: String): PackageVariant? {
        value?.packages?.forEach { pkg ->
            if (pkg.packageName == packageName) return pkg.variants[0]
        }
        return null
    }

    fun liveInstallablePackageInfo(): LiveData<List<InstallablePackageInfo>> =
        installablePackageInfo

    @RequiresPermission(Manifest.permission.INTERNET)
    fun refreshMetadata(force: Boolean = false, callback: (error: MetadataCallBack) -> Unit) {
        if (metadata.value != null && !force) {
            return
        }

        CoroutineScope(scopeMetadataRefresh).launch(Dispatchers.IO) {
            try {
                metaDataHelper.downloadNdVerifyMetadata { response ->
                    metadata.postValue(response)
                    callback.invoke(MetadataCallBack.Success(response.timestamp))
                    updateInstalledAppInfo()
                }

            } catch (e: GeneralSecurityException) {
                callback.invoke(MetadataCallBack.SecurityError(e))
            } catch (e: JSONException) {
                callback.invoke(MetadataCallBack.JSONError(e))
            } catch (e: DecoderException) {
                callback.invoke(MetadataCallBack.DecoderError(e))
            } catch (e: UnknownHostException) {
                callback.invoke(MetadataCallBack.UnknownHostError(e))
            } catch (e: SSLHandshakeException) {
                callback.invoke(MetadataCallBack.SecurityError(e))
            }
        }
    }

    private fun updateInstalledAppInfo(pkgName: String? = null) {

        val metaData = metadata.value ?: return
        val currentInfos = installablePackageInfo.value ?: emptyList()

        for (p: Package in metaData.packages) {
            if (p.packageName == pkgName) continue
            if (apps.containsKey(p.packageName) && apps[p.packageName] !is InstallStatus.Installable) continue
            updatePackageInfoInfo(p.packageName, p.variants[0])
        }

        val result = mutableListOf<InstallablePackageInfo>()

        if (currentInfos.isNotEmpty()) {
            for (info in currentInfos) {
                result.add(InstallablePackageInfo(info.name, apps[info.name]!!))
            }
        } else {
            apps.forEach { (appsPkgName, status) ->
                result.add(InstallablePackageInfo(appsPkgName, status))
            }
        }

        installablePackageInfo.postValue(result)
    }

    private fun updatePackageInfoInfo(pkgName: String, p: PackageVariant? = null) {
        val appVersion = getInstalledAppVersionCode(pkgName)
        val packageInfo = p ?: metadata.forPackage(pkgName)
        if (appVersion > 0) {
            apps[pkgName] = InstallStatus.Installed(
                appVersion > 0,
                appVersion,
                packageInfo?.versionCode?.toLong() ?: 0
            )
            val installerInfo = packageManager.getInstallSourceInfo(pkgName)
            if (!packageName.equals(installerInfo.initiatingPackageName)) {
                apps[pkgName] = InstallStatus.ReinstallRequired(
                    appVersion,
                    packageInfo?.versionCode?.toLong() ?: 0
                )
            }
        } else {
            apps[pkgName] = InstallStatus.Installable(
                packageInfo?.versionCode?.toLong() ?: 0
            )
        }
    }

    private fun getInstalledAppVersionCode(packageName: String): Long {
        return try {
            val appInfo = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA
            )

            appInfo.longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        }
    }

    @Suppress("SameParameterValue")
    private fun downloadPackages(
        variant: PackageVariant,
        requestInstall: Boolean,
        callback: (error: DownloadCallBack) -> Unit
    ) {
        executor.execute {
            val appVersion = getInstalledAppVersionCode(variant.pkgName)

            val taskId = SystemClock.currentThreadTimeMillis().toInt()
            val taskCompleted = TaskInfo(taskId, "", DOWNLOAD_TASK_FINISHED)
            var taskSuccess = false
            var errorMsg = ""
            CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
                try {
                    val apks = apkDownloadHelper.downloadNdVerifySHA256(variant)
                    { read: Long, total: Long, doneInPercent: Double, taskCompleted: Boolean ->
                        if (doneInPercent != 1.0 && read.toInt() != total.toInt()) {
                            apps[variant.pkgName] = InstallStatus.Downloading(
                                appVersion > 0,
                                appVersion,
                                variant.versionCode.toLong(),
                                total.toInt(),
                                read.toInt(),
                                doneInPercent.toInt(),
                                taskCompleted
                            )
                        }
                        val taskInfo = TaskInfo(
                            taskId,
                            "${getString(R.string.downloading)} ${variant.pkgName} ...",
                            doneInPercent.toInt()
                        )
                        tasksInfo.updateStatus(tasksInfo.replaceOrPut(taskInfo))
                        updateInstalledAppInfo(variant.pkgName)
                    }
                    if (requestInstall && apks.isNotEmpty()) {
                        requestInstall(apks, variant.pkgName)
                    }
                    tasksInfo.updateStatus(tasksInfo.replaceOrPut(taskCompleted))
                    taskSuccess = true
                } catch (e: IOException) {
                    errorMsg = e.localizedMessage ?: ""
                    callback.invoke(DownloadCallBack.IoError(e))
                } catch (e: GeneralSecurityException) {
                    errorMsg = e.localizedMessage ?: ""
                    callback.invoke(DownloadCallBack.SecurityError(e))
                } catch (e: UnknownHostException) {
                    errorMsg = e.localizedMessage ?: ""
                    callback.invoke(DownloadCallBack.UnknownHostError(e))
                } catch (e: SSLHandshakeException) {
                    errorMsg = e.localizedMessage ?: ""
                    callback.invoke(DownloadCallBack.SecurityError(e))
                } finally {
                    tasksInfo.updateStatus(tasksInfo.replaceOrPut(taskCompleted, taskSuccess))
                    if (!taskSuccess) {
                        apps[variant.pkgName] = InstallStatus.Failed(
                            errorMsg,
                            appVersion,
                            variant.versionCode.toLong()
                        )
                        updateInstalledAppInfo(variant.pkgName)
                    }
                }
            }
        }
    }

    fun getTasksInfo(): LiveData<Map<Int, TaskInfo>> = tasksInfo

    private fun MutableLiveData<Map<Int, TaskInfo>>.replaceOrPut(
        updatedTask: TaskInfo,
        taskSuccess: Boolean = false
    ): Map<Int, TaskInfo> {
        val result: MutableMap<Int, TaskInfo> = mutableMapOf()
        value?.let { result.putAll(it) }

        result[updatedTask.id] = updatedTask
        if (taskSuccess) {
            result.remove(updatedTask.id)
        }
        return result
    }

    private fun MutableLiveData<Map<Int, TaskInfo>>.updateStatus(infos: Map<Int, TaskInfo>) {
        postValue(infos)
        if (!isServiceRunning) {
            startService(Intent(this@App, KeepAppActive::class.java))
        }
    }

    fun updateServiceStatus(isRunning: Boolean) {
        isServiceRunning = isRunning
    }

    private fun requestInstall(apks: List<File>, pkgName: String) {
        if (isActivityRunning != null) {
            apps[pkgName] = InstallStatus.Installing(
                true,
                metadata.forPackage(pkgName)?.versionCode?.toLong() ?: 0,
                true
            )
            val sessionId = PackageManagerHelper().install(this, apks)
            sessionNdApps[sessionId] = pkgName
            conformationAwaitedPackages.remove(pkgName)
            updateInstalledAppInfo(pkgName)
        } else {
            conformationAwaitedPackages[pkgName] = apks
        }

    }

    fun handleOnClick(
        pkgName: String,
        callback: (result: String) -> Unit
    ) {
        val status = apps[pkgName]
        val variant = metadata.forPackage(pkgName)

        if (status == null || variant == null) {
            callback.invoke(getString(R.string.syncUnfinished))
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            callback.invoke(getString(R.string.allowUnknownSources))
            Toast.makeText(this, getString(R.string.allowUnknownSources), Toast.LENGTH_SHORT).show()
            isActivityRunning?.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
                    Uri.parse(String.format("package:%s", packageName))
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {
            when (status) {
                is InstallStatus.Downloading -> {
                    callback.invoke("${status.downloadedPercent}% ${getString(R.string.downloaded)} ")
                }
                is InstallStatus.Installable -> {
                    apps[variant.pkgName] = InstallStatus.Installable(
                        variant.versionCode.toLong(),
                        true
                    )
                    updateInstalledAppInfo(variant.pkgName)
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.Installed -> {
                    callback.invoke("${getString(R.string.uninstalling)} $pkgName")
                    PackageManagerHelper().uninstall(applicationContext, pkgName)
                }
                is InstallStatus.Installing -> {
                    callback.invoke(getString(R.string.installationInProgress))
                }
                is InstallStatus.Uninstalling -> {
                    callback.invoke(getString(R.string.uninstallationInProgress))
                }
                is InstallStatus.Updated -> {
                    callback.invoke(getString(R.string.alreadyUpToDate))
                    PackageManagerHelper().uninstall(applicationContext, pkgName)
                }
                is InstallStatus.Failed -> {
                    callback.invoke(getString(R.string.reinstalling))
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.Updatable -> {
                    callback.invoke("${getString(R.string.updating)} $pkgName")
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.ReinstallRequired -> {
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(appsChangesReceiver)
        executor.shutdown()
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val appsChangesFilter = IntentFilter()
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_ADDED) //installed
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REPLACED) // updated (i.e : v1 to v1.1)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED) //uninstall finished
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REMOVED) //uninstall started
        appsChangesFilter.addDataScheme("package")

        registerReceiver(
            appsChangesReceiver,
            appsChangesFilter
        )

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                //nothing to do here
            }

            override fun onActivityStarted(activity: Activity) {
                //nothing to do here
            }

            override fun onActivityResumed(activity: Activity) {
                isActivityRunning = activity
                conformationAwaitedPackages.forEach { (packageName, apks) ->
                    requestInstall(apks, packageName)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                isActivityRunning = null
            }

            override fun onActivityStopped(activity: Activity) {
                //nothing to do here
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
                //nothing to do here
            }

            override fun onActivityDestroyed(activity: Activity) {
                //nothing to do here
            }
        })

        context = WeakReference(this)
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannelCompat.Builder(
            BACKGROUND_SERVICE_CHANNEL,
            NotificationManager.IMPORTANCE_LOW
        )
            .setName("Background tasks")
            .setDescription("This channel is used to display silent notification for background tasks")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(channel)
    }

}
