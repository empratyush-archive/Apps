package org.grapheneos.apps.client.utils.network

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.grapheneos.apps.client.di.DaggerHttpHelperComponent
import org.grapheneos.apps.client.di.HttpHelperComponent.Companion.defaultConfigBuild
import org.grapheneos.apps.client.item.PackageVariant
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.net.ssl.SSLHandshakeException

class ApkDownloadHelper constructor(private val context: Context) {

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

        val result = mutableListOf<File>()

        val downloadDir =
            File("${context.cacheDir.absolutePath}/downloadedPkg/${variant.versionCode}/${variant.pkgName}")

        downloadDir.mkdirs()

        variant.packagesInfo.forEach { (fileName, sha256Hash) ->
            withContext(Dispatchers.IO) {
                val downloadedFile = File(downloadDir.absolutePath, fileName)
                val uri =
                    "https://apps.grapheneos.org/packages/${variant.pkgName}/${variant.versionCode}/${fileName}"

                if (downloadedFile.exists() && verifyHash(downloadedFile, sha256Hash)) {
                    result.add(downloadedFile)
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
                    result.add(downloadedFile)

                }

            }
        }
        return result
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

