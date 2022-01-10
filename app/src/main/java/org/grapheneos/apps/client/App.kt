@file:Suppress("BlockingMethodInNonBlockingContext")

package org.grapheneos.apps.client

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
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
import org.grapheneos.apps.client.item.DownloadCallBack.Companion.toUiMsg
import org.grapheneos.apps.client.item.DownloadStatus
import org.grapheneos.apps.client.item.InstallStatus
import org.grapheneos.apps.client.item.InstallStatus.Companion.createFailed
import org.grapheneos.apps.client.item.MetadataCallBack
import org.grapheneos.apps.client.item.PackageInfo
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.SeamlessUpdateResponse
import org.grapheneos.apps.client.item.SessionInfo
import org.grapheneos.apps.client.item.TaskInfo
import org.grapheneos.apps.client.service.KeepAppActive
import org.grapheneos.apps.client.service.SeamlessUpdaterJob
import org.grapheneos.apps.client.ui.mainScreen.ChannelPreferenceManager
import org.grapheneos.apps.client.utils.ActivityLifeCycleHelper
import org.grapheneos.apps.client.utils.PackageManagerHelper.Companion.pmHelper
import org.grapheneos.apps.client.utils.network.ApkDownloadHelper
import org.grapheneos.apps.client.utils.network.MetaDataHelper
import org.grapheneos.apps.client.utils.sharedPsfsMgr.JobPsfsMgr
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
        const val AUTO_UPDATE_CHANNEL = "autoUpdate"
        const val DOWNLOAD_TASK_FINISHED = 1000
        private lateinit var context: WeakReference<Context>

        private const val SEAMLESS_UPDATER_JOB_ID = 1000

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

    private val jobPsfsMgr by lazy {
        JobPsfsMgr(this)
    }

    /*Coroutine scope and jobs var*/
    private val scopeApkDownload by lazy { Dispatchers.IO }
    private val scopeMetadataRefresh by lazy { Dispatchers.IO }
    private lateinit var seamlessUpdaterScope: CompletableJob
    private lateinit var refreshJob: CompletableJob
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

    private fun refreshMetadata(): MetadataCallBack {
        try {
            val res = metaDataHelper.downloadNdVerifyMetadata { response ->
                response.packages.forEach {
                    val value = it.value
                    val pkgName = value.packageName
                    val channelPref = ChannelPreferenceManager
                        .getPackageChannel(this@App, pkgName)
                    val channelVariant = value.variants[channelPref]
                        ?: value.variants[App.getString(R.string.channel_default)]!!
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
            }
            return MetadataCallBack.Success(res.timestamp)
        } catch (e: GeneralSecurityException) {
            return MetadataCallBack.SecurityError(e)
        } catch (e: JSONException) {
            return MetadataCallBack.JSONError(e)
        } catch (e: DecoderException) {
            return MetadataCallBack.DecoderError(e)
        } catch (e: UnknownHostException) {
            return MetadataCallBack.UnknownHostError(e)
        } catch (e: SSLHandshakeException) {
            return MetadataCallBack.SecurityError(e)
        }
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
            callback.invoke(refreshMetadata())
            refreshJob.complete()
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

    private suspend fun downloadPackages(
        variant: PackageVariant,
    ): DownloadCallBack {
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

        val taskCompleted = TaskInfo(taskId, "", DOWNLOAD_TASK_FINISHED)
        var taskSuccess = false
        var errorMsg = ""
        try {
            val apks = apkDownloadHelper.downloadNdVerifySHA256(variant = variant)
            { read: Long, total: Long, doneInPercent: Double, completed: Boolean ->
                if (doneInPercent == -1.0) return@downloadNdVerifySHA256
                packagesInfo[variant.pkgName] =
                    packagesInfo[variant.pkgName]!!.withUpdatedDownloadStatus(
                        DownloadStatus.Downloading(
                            downloadSize = total.toInt(),
                            downloadedSize = read.toInt(),
                            downloadedPercent = doneInPercent,
                            completed = completed
                        )
                    ).withUpdatedTask(
                        TaskInfo(
                            taskId,
                            "${getString(R.string.downloading)} ${variant.appName} ...",
                            doneInPercent.toInt()
                        )
                    )
                updateLiveData()
            }
            taskSuccess = true
            return DownloadCallBack.Success(apks = apks)
        } catch (e: IOException) {
            errorMsg = e.localizedMessage ?: ""
            return DownloadCallBack.IoError(e)
        } catch (e: GeneralSecurityException) {
            errorMsg = e.localizedMessage ?: ""
            return DownloadCallBack.SecurityError(e)
        } catch (e: UnknownHostException) {
            errorMsg = e.localizedMessage ?: ""
            return DownloadCallBack.UnknownHostError(e)
        } catch (e: SSLHandshakeException) {
            errorMsg = e.localizedMessage ?: ""
            return DownloadCallBack.SecurityError(e)
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
        }
    }

    private fun downloadPackages(
        variant: PackageVariant,
        callback: (error: DownloadCallBack) -> Unit
    ) {
        executor.execute {
            CoroutineScope(scopeApkDownload).launch(Dispatchers.IO) {

                val result = downloadPackages(variant)
                if (result is DownloadCallBack.Success) {
                    val apks = result.apks
                    if (apks.isNotEmpty()) {
                        requestInstall(apks, variant.pkgName)
                    }
                }
                callback.invoke(result)
            }
        }
    }

    fun updateServiceStatus(isRunning: Boolean) {
        isServiceRunning = isRunning
    }

    private fun requestInstall(apks: List<File>, pkgName: String, backgroundMode: Boolean = false) {
        val pkgInfo = packagesInfo[pkgName]!!
        if (isActivityRunning != null || backgroundMode) {
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

    fun downloadUpdatableAppsNow() {
        packagesInfo.forEach { info ->
            val installStatus = info.value.installStatus
            val variant = info.value.selectedVariant

            if (installStatus is InstallStatus.Updatable) {
                handleOnClick(variant.pkgName) {}
            }
        }
    }

    fun openAppDetails(pkgName: String) {
        isActivityRunning?.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse(String.format("package:%s", pkgName)))
                .addCategory(Intent.CATEGORY_DEFAULT), null
        )
    }

    fun uninstallPackage(pkgName: String, callback: (result: String) -> Unit) {
        callback.invoke("${getString(R.string.uninstalling)} $pkgName")
        pmHelper().uninstall(pkgName)
    }

    fun handleOnVariantChange(
        packageName: String,
        channel: String,
        callback: (info: PackageInfo) -> Unit
    ) {
        val infoToCheck = packagesInfo[packageName] ?: return
        val channelVariants = infoToCheck.allVariant
        var channelVariant: PackageVariant = infoToCheck.selectedVariant
        channelVariants.forEach { packageVariant ->
            if (packageVariant.type == channel) {
                channelVariant = packageVariant
            }
        }
        val installStatus = getInstalledStatus(packageName, channelVariant.versionCode.toLong())
        packagesInfo[packageName] = infoToCheck.withUpdatedVariant(channelVariant)
            .withUpdatedInstallStatus(installStatus)
        callback.invoke(packagesInfo[packageName]!!)
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
                    downloadPackages(variant) { error -> callback.invoke(error.genericMsg) }
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
                    downloadPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.ReinstallRequired -> {
                    downloadPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
                is InstallStatus.Failed -> {
                    callback.invoke(getString(R.string.reinstalling))
                    downloadPackages(variant)
                    { error -> callback.invoke(error.toUiMsg()) }
                }
            }
        }
    }

    private fun isSeamlessUpdateRunning() =
        this::seamlessUpdaterScope.isInitialized
                && seamlessUpdaterScope.isActive
                && !seamlessUpdaterScope.isCompleted && !seamlessUpdaterScope.isCancelled

    fun seamlesslyUpdateApps(onFinished: (result: SeamlessUpdateResponse) -> Unit) {
        if (isSeamlessUpdateRunning()) {
            return
        }
        seamlessUpdaterScope = Job()

        if (isActivityRunning != null) {
            // don't auto update if app is in foreground
            onFinished.invoke(SeamlessUpdateResponse())
            return
        }

        if (packagesInfo.isNotEmpty()) {
            packagesInfo.clear()
        }

        if (this::refreshJob.isInitialized && refreshJob.isActive
            && !refreshJob.isCompleted && !refreshJob.isCancelled
        ) {
            refreshJob.cancel()
        }

        refreshJob = Job()

        CoroutineScope(seamlessUpdaterScope + Dispatchers.IO).launch {

            val metaData = refreshMetadata()
            if (!metaData.isSuccessFull) {
                //sync failed, will try again
                onFinished.invoke(SeamlessUpdateResponse())
                return@launch
            }

            val updatedPackages = mutableListOf<String>()
            val updateFailedPackages = mutableListOf<String>()

            packagesInfo.forEach { info ->
                val installStatus = info.value.installStatus
                val variant = info.value.selectedVariant

                if (installStatus is InstallStatus.Updatable) {
                    val downloadResult = downloadPackages(variant)
                    if (downloadResult is DownloadCallBack.Success) {
                        requestInstall(downloadResult.apks, variant.pkgName, true)
                        updatedPackages.add(variant.appName)
                    } else {
                        updateFailedPackages.add(variant.appName)
                    }
                }
            }

            onFinished.invoke(
                SeamlessUpdateResponse(
                    updatedPackages,
                    updateFailedPackages,
                    true
                )
            )
        }
    }

    private fun cancelScheduleAutoUpdate() {
        val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(SEAMLESS_UPDATER_JOB_ID)
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

            if (foregroundServiceNeeded && !isSeamlessUpdateRunning()) {
                startService(
                    Intent(
                        this@App,
                        KeepAppActive::class.java
                    )
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)

        createNotificationChannel()

        val appsChangesFilter = IntentFilter()
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REPLACED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
        appsChangesFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        appsChangesFilter.addDataScheme("package")

        registerReceiver(appsChangesReceiver, appsChangesFilter)

        registerActivityLifecycleCallbacks(ActivityLifeCycleHelper { activity ->
            isActivityRunning = activity
            if (isActivityRunning != null) {
                conformationAwaitedPackages.forEach { (packageName, apks) ->
                    requestInstall(apks, packageName)
                }
            }
        })

        context = WeakReference(this)
        packageLiveData.observeForever(observer)

        jobPsfsMgr.onJobPsfsChanged { isEnabled, networkType, time ->
            if (isEnabled) {
                val jobInfo = JobInfo.Builder(
                    SEAMLESS_UPDATER_JOB_ID,
                    ComponentName(this, SeamlessUpdaterJob::class.java)
                ).setRequiredNetworkType(networkType)
                    .setPersisted(true)
                    .setPeriodic(time)
                    .build()

                val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                jobScheduler.schedule(jobInfo)
            } else {
                cancelScheduleAutoUpdate()
            }
        }

    }

    private fun createNotificationChannel() {

        val channelBackgroundTask = NotificationChannelCompat.Builder(
            BACKGROUND_SERVICE_CHANNEL,
            NotificationManager.IMPORTANCE_LOW
        )
            .setName("Background tasks")
            .setDescription("Silent notification for background tasks")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val channelAutoUpdate = NotificationChannelCompat.Builder(
            AUTO_UPDATE_CHANNEL,
            NotificationManager.IMPORTANCE_LOW
        )
            .setName("Seamless updates")
            .setDescription("Notification regarding seamless updates")
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .setLightsEnabled(false)
            .build()

        val nm = NotificationManagerCompat.from(this)
        nm.createNotificationChannel(channelBackgroundTask)
        nm.createNotificationChannel(channelAutoUpdate)
    }

    fun isActivityRunning() = isActivityRunning != null

}
