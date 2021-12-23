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
import androidx.lifecycle.Observer
import com.google.android.material.color.DynamicColors
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.item.DownloadCallBack
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.InstallStatus.Companion.createFailed
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.SessionInfo
import org.grapheneos.apps.client.item.TaskInfo
import org.grapheneos.apps.client.service.KeepAppActive
import org.grapheneos.apps.client.ui.settings.MainSettings
import org.grapheneos.apps.client.ui.mainScreen.ChannelPreferenceManager
import org.grapheneos.apps.client.utils.PackageManagerHelper.Companion.pmHelper
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
import kotlin.random.Random

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

    /*Injectable member var*/
    @Inject
    lateinit var metaDataHelper: MetaDataHelper

    @Inject
    lateinit var apkDownloadHelper: ApkDownloadHelper

    /*Application wide singleton object*/
    private val executor = Executors.newSingleThreadExecutor()

    private var isActivityRunning: Activity? = null
    private var isServiceRunning = false

    /*Application info object*/
    private val sessionIdsMap = mutableMapOf<Int, String>()
    private val conformationAwaitedPackages = mutableMapOf<String, List<File>>()

    private val packagesInfo: MutableMap<String, PackageInfo> = mutableMapOf()
    private val packagesMutableLiveData = MutableLiveData<Map<String, PackageInfo>>()
    val packageLiveData: LiveData<Map<String, PackageInfo>> = packagesMutableLiveData

    /*Coroutine scope and jobs var*/
    private val scopeApkDownload by lazy { Dispatchers.IO }
    private val scopeMetadataRefresh by lazy { Dispatchers.IO }
    private lateinit var refreshJob: CompletableJob
    private lateinit var autoDownloadJob: CompletableJob
    private val isAutoDownloadEnabled by lazy { MainSettings.getAutoDownload(this) }
    private var taskIdSeed = Random(SystemClock.currentThreadTimeMillis().toInt()).nextInt(1, 1000)
    private val appsChangesReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            val action = intent?.action ?: return
            val pkgName = intent.data?.schemeSpecificPart ?: return

            val installedVersion = try {
                val appInfo = packageManager.getPackageInfo(
                    pkgName,
                    PackageManager.GET_META_DATA
                )
                appInfo.longVersionCode
            } catch (e: PackageManager.NameNotFoundException) {
                -1L
            }

            val info = packagesInfo[pkgName]
            if (!packagesInfo.containsKey(pkgName) || info == null) {
                //If other package is installed or uninstalled we don't care
                return
            }
            val latestVersion = info.selectedVariant.versionCode.toLong()

            when (action) {
                Intent.ACTION_PACKAGE_ADDED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Installed(installedVersion, latestVersion)
                    )
                }
                Intent.ACTION_PACKAGE_REPLACED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Updated(installedVersion, latestVersion)
                    )
                }
                Intent.ACTION_PACKAGE_FULLY_REMOVED,
                Intent.ACTION_PACKAGE_REMOVED -> {
                    packagesInfo[pkgName] = info.withUpdatedInstallStatus(
                        InstallStatus.Installable(latestVersion)
                    )
                }
            }
            updateLiveData()
        }
    }

    private fun updateLiveData() {
        packagesMutableLiveData.postValue(packagesInfo)
    }

    fun installIntentResponse(sessionId: Int, errorMsg: String, userDeclined: Boolean = false) {
        val pkgName = sessionIdsMap[sessionId] ?: return
        val info = packagesInfo[pkgName] ?: return
        packagesInfo[pkgName] = info.withUpdatedInstallStatus(
            info.installStatus.createFailed(
                errorMsg,
                if (userDeclined) App.getString(R.string.denied) else null
            )
        )
        updateLiveData()
    }

    @RequiresPermission(Manifest.permission.INTERNET)
    fun refreshMetadata(force: Boolean = false, callback: (error: MetadataCallBack) -> Unit) {
        if ((packagesInfo.isNotEmpty() && !force) ||
            (this::refreshJob.isInitialized && refreshJob.isActive
            && !refreshJob.isCompleted && !refreshJob.isCancelled)
        ) {
            return
        }

        refreshJob = Job()
        CoroutineScope(scopeMetadataRefresh + refreshJob).launch(Dispatchers.IO) {
            try {
                metaDataHelper.downloadNdVerifyMetadata { response ->
                    response.packages.forEach {
                        val value = it.value
                        val pkgName = value.packageName
                        val channelPref = ChannelPreferenceManager
                            .getPackageChannel(this@App, pkgName)
                        val channelVariant = if (value.variants[channelPref] != null) {
                            value.variants[channelPref]!!
                        } else {
                            ChannelPreferenceManager.savePackageChannel(this@App, pkgName)
                            value.variants[getString(R.string.channel_default)]!!
                                                 }
                        val installStatus = getInstalledStatus(
                            pkgName,
                            channelVariant.versionCode.toLong()
                        )

                        val info = packagesInfo.getOrDefault(
                            pkgName,
                            PackageInfo(
                                id = pkgName,
                                sessionInfo = SessionInfo(),
                                selectedVariant = channelVariant,
                                allVariant = value.variants.values.toList(),
                                installStatus = installStatus
                            )
                        )
                        packagesInfo[pkgName] = info.withUpdatedInstallStatus(installStatus)

                        updateLiveData()
                    }
                    callback.invoke(MetadataCallBack.Success(response.timestamp))
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
            } finally {
                refreshJob.complete()
            }
        }
    }

    private fun getInstalledStatus(pkgName: String, latestVersion: Long): InstallStatus {
        val pm = packageManager
        return try {
            val appInfo = pm.getPackageInfo(pkgName, 0)
            val installerInfo = pm.getInstallSourceInfo(pkgName)
            val currentVersion = appInfo.longVersionCode

            if (packageName.equals(installerInfo.initiatingPackageName)) {
                if (currentVersion < latestVersion) {
                    InstallStatus.Updatable(currentVersion, latestVersion)
                } else {
                    InstallStatus.Installed(currentVersion, latestVersion)
                }
            } else {
                InstallStatus.ReinstallRequired(currentVersion, latestVersion)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return InstallStatus.Installable(latestVersion)
        }
    }

    @Suppress("SameParameterValue")
    private fun downloadPackages(
        variant: PackageVariant,
        requestInstall: Boolean,
        callback: (error: DownloadCallBack) -> Unit
    ) {
        taskIdSeed++
        val taskId = taskIdSeed
        packagesInfo[variant.pkgName] = packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
            DownloadStatus.Downloading(
                App.getString(R.string.processing),
                0,
                0,
                0.0,
                false
            )
        ).withUpdatedTask(TaskInfo(taskId, "starting download", 0))
        updateLiveData()
        executor.execute {
            val taskCompleted = TaskInfo(taskId, "", DOWNLOAD_TASK_FINISHED)
            var taskSuccess = false
            var errorMsg = ""
            val extendedScopeApkDownload = if (isAutoDownloadEnabled) {
                autoDownloadJob = Job()
                scopeApkDownload + autoDownloadJob
            } else scopeApkDownload
            CoroutineScope(extendedScopeApkDownload).launch(Dispatchers.IO) {
                try {
                    val apks = apkDownloadHelper.downloadNdVerifySHA256(variant = variant)
                    { read: Long, total: Long, doneInPercent: Double, taskCompleted: Boolean ->
                        if (doneInPercent == -1.0) return@downloadNdVerifySHA256
                        packagesInfo[variant.pkgName] =
                            packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
                                DownloadStatus.Downloading(
                                    downloadSize = total.toInt(),
                                    downloadedSize = read.toInt(),
                                    downloadedPercent = doneInPercent,
                                    completed = taskCompleted
                                )
                            ).withUpdatedTask(
                                TaskInfo(
                                    taskId,
                                    "${getString(R.string.downloading)} ${variant.pkgName} ...",
                                    doneInPercent.toInt()
                                )
                            )
                        updateLiveData()
                    }
                    if (requestInstall && apks.isNotEmpty()) {
                        requestInstall(apks, variant.pkgName)
                    }
                    callback.invoke(DownloadCallBack.Success())
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
                    if (!taskSuccess) {
                        packagesInfo[variant.pkgName] =
                            packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
                                DownloadStatus.Failed(errorMsg)
                            ).withUpdatedTask(taskCompleted)
                    } else {
                        packagesInfo[variant.pkgName] =
                            packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(null)
                                .withUpdatedTask(taskCompleted)
                    }
                    updateLiveData()
                    if (isAutoDownloadEnabled) autoDownloadJob.complete()
                }
            }
        }
    }

    fun updateServiceStatus(isRunning: Boolean) {
        isServiceRunning = isRunning
    }

    private fun requestInstall(apks: List<File>, pkgName: String) {
        val pkgInfo = packagesInfo[pkgName]!!
        if (isActivityRunning != null) {
            val sessionId = this@App.pmHelper().install(apks)
            sessionIdsMap[sessionId] = pkgName
            packagesInfo[pkgName] = pkgInfo
                .withUpdatedSession(
                    SessionInfo(sessionId, true)
                ).withUpdatedInstallStatus(
                    InstallStatus.Installing(
                        true,
                        pkgInfo.selectedVariant.versionCode.toLong(),
                        true
                    )
                )
            updateLiveData()
            conformationAwaitedPackages.remove(pkgName)
        } else {
            conformationAwaitedPackages[pkgName] = apks
        }

    }

    fun maybeAutoDownload(callback: (result: String) -> Unit) {
        if (this::autoDownloadJob.isInitialized && autoDownloadJob.isActive
            && !autoDownloadJob.isCompleted && !autoDownloadJob.isCancelled) {
            return
        }
        packagesInfo.keys.forEach { pkgName ->
            val status = packagesInfo[pkgName]?.installStatus
            val variant = packagesInfo[pkgName]?.selectedVariant
            if (status !is InstallStatus.Updatable || variant == null) {
                return@forEach
            }
            handleOnClick(pkgName, callback)
        }
    }

    fun handleOnClick(
        pkgName: String,
        callback: (result: String) -> Unit
    ) {
        val status = packagesInfo[pkgName]?.installStatus
        val variant = packagesInfo[pkgName]?.selectedVariant

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
                is InstallStatus.Installable -> {
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.Installed -> {
                    callback.invoke("${getString(R.string.uninstalling)} $pkgName")
                    pmHelper().uninstall(pkgName)
                }
                is InstallStatus.Installing -> {
                    callback.invoke(getString(R.string.installationInProgress))
                }
                is InstallStatus.Uninstalling -> {
                    callback.invoke(getString(R.string.uninstallationInProgress))
                }
                is InstallStatus.Updated -> {
                    callback.invoke(getString(R.string.alreadyUpToDate))
                    pmHelper().uninstall(pkgName)
                }
                is InstallStatus.Updatable -> {
                    callback.invoke("${getString(R.string.updating)} $pkgName")
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.ReinstallRequired -> {
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
                is InstallStatus.Failed -> {
                    callback.invoke(getString(R.string.reinstalling))
                    downloadPackages(variant, true) { error -> callback.invoke(error.genericMsg) }
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(appsChangesReceiver)
        packageLiveData.removeObserver(observer)
        executor.shutdown()
    }


    private val observer = Observer<Map<String, PackageInfo>> { infos ->
        if (!isServiceRunning) {
            var foregroundServiceNeeded = false
            infos.values.forEach { packageInfo ->
                val task = packageInfo.taskInfo
                if (task.progress != DOWNLOAD_TASK_FINISHED) {
                    foregroundServiceNeeded = true
                }
            }

            if (foregroundServiceNeeded) {
                startService(Intent(this@App, KeepAppActive::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

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
        packageLiveData.observeForever(observer)

        /*if(packagesInfo.isEmpty()){
            refreshMetadata {  }
        }*/
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
