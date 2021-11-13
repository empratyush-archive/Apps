package org.grapheneos.apps.client.utils.network

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.grapheneos.apps.client.di.DaggerHttpHelperComponent
import org.grapheneos.apps.client.di.HttpHelperComponent.Companion.defaultConfigBuild
import org.grapheneos.apps.client.item.PackageVariant
import org.grapheneos.apps.client.item.Progress
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

class ApkDownloadHelper constructor(private val context: Context) {

    @DelicateCoroutinesApi
    @Throws(
        UnknownHostException::class,
        GeneralSecurityException::class,
        IOException::class,
        SSLHandshakeException::class
    )
    @RequiresPermission(Manifest.permission.INTERNET)
    suspend fun downloadNdVerifySHA256(
        variant: PackageVariant,
        progressListener: (read: Long, total: Long, doneInPercent: Double, taskCompleted: Boolean) -> Unit,
    ): List<File> {

        val downloadDir =
            File("${context.cacheDir.absolutePath}/downloadedPkg/${variant.versionCode}/${variant.pkgName}")

        downloadDir.mkdirs()

        val completeProgress = mutableMapOf<String, Progress>()
        val size = variant.packagesInfo.size

        val downloadTasks = CoroutineScope(Dispatchers.IO).async {
            variant.packagesInfo.map { (fileName, sha256Hash) ->
                downloadAsync(downloadDir, variant, fileName, sha256Hash) { newProgress ->

                    var read = 0L
                    var total = 0L
                    var completed = true
                    var calculationSize = 0

                    completeProgress[fileName] = newProgress
                    completeProgress.forEach { (_, progress) ->
                        read += progress.read
                        total += progress.total
                        completed = completed && progress.taskCompleted
                        calculationSize++
                    }

                    val shouldCompute = calculationSize == size && total.toInt() != 0
                    val doneInPercent = if (shouldCompute) (read * 100.0) / total else -1.0

                    progressListener.invoke(
                        read,
                        total,
                        doneInPercent,
                        completed
                    )
                }
            }
        }
        return downloadTasks.await().awaitAll()
    }


    @DelicateCoroutinesApi
    private fun downloadAsync(downloadDir : File, variant: PackageVariant, fileName : String, sha256Hash: String,
                              progressListener: (progress : Progress) -> Unit
    ) : Deferred<File> = GlobalScope.async {
        val downloadedFile = File(downloadDir.absolutePath, fileName)
        val uri =
            "https://apps.grapheneos.org/packages/${variant.pkgName}/${variant.versionCode}/${fileName}"

        if (downloadedFile.exists() && verifyHash(downloadedFile, sha256Hash)) {
            progressListener.invoke(
                Progress(
                    downloadedFile.length(),
                    downloadedFile.length(),
                    100.0,
                    true
                )
            )
             downloadedFile
        } else {

            DaggerHttpHelperComponent.builder()
                .defaultConfigBuild()
                .file(downloadedFile)
                .uri(uri)
                .addProgressListener(progressListener)
                .build()
                .downloader()
                .saveToFile()

            if (!verifyHash(downloadedFile, sha256Hash)) {
                downloadedFile.delete()
                throw GeneralSecurityException("Hash didn't matched")
            }
            downloadedFile
        }
    }

    @Throws(NoSuchAlgorithmException::class, GeneralSecurityException::class)
    private fun verifyHash(downloadedFile: File, sha256Hash: String): Boolean {
        try {
            val downloadedFileHash = bytesToHex(
                MessageDigest.getInstance("SHA-256").digest(downloadedFile.readBytes())
            )
            if (sha256Hash == downloadedFileHash) return true
        } catch (e: NoSuchAlgorithmException) {
            throw GeneralSecurityException("SHA-256 is not supported by device")
        }
        return false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

}
