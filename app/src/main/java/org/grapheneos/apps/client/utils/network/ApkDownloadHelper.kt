package org.grapheneos.apps.client.utils.network

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Source
import okio.Buffer
import okio.ForwardingSource
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import org.grapheneos.apps.client.item.PackageVariant
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import javax.net.ssl.SSLHandshakeException

class ApkDownloadHelper constructor(
    private val okHttpClient: OkHttpClient,
    private val context: Context
) {

    @Throws(UnknownHostException::class,
        GeneralSecurityException::class,
        IOException::class,
        SSLHandshakeException::class
    )
    @RequiresPermission(Manifest.permission.INTERNET)
    suspend fun downloadNdVerifySHA256(
        variant: PackageVariant,
        progressListener: (read: Long, total: Long, doneInPercent: Long, taskCompleted: Boolean) -> Unit,
    ): List<File> {

        val result = mutableListOf<File>()

        val downloadDir =
            File("${context.cacheDir.absolutePath}/downloadedPkg/${variant.versionCode}/${variant.pkgName}")

        downloadDir.mkdirs()

        variant.packagesInfo.forEach { (fileName, sha256Hash) ->
            withContext(Dispatchers.IO) {
                val downloadedFile = File(downloadDir.absolutePath, fileName)
                val request = Request.Builder()
                    .url("https://apps.grapheneos.org/packages/${variant.pkgName}/${variant.versionCode}/${fileName}")
                    .build()

                //if file already exist and it match given hash instead of downloading it again just return it as it is
                // but only if it match with given hash otherwise remove it and download it fresh
                if (downloadedFile.exists() && verifyHash(downloadedFile, sha256Hash)) {
                    result.add(downloadedFile)
                } else {
                    if (downloadedFile.exists()) {
                        downloadedFile.delete()
                    }
                    val response = okHttpClient.newBuilder()
                        .readTimeout(Duration.ofMinutes(5))
                        .writeTimeout(Duration.ofMinutes(5))
                        .callTimeout(Duration.ofSeconds(120))
                        .addInterceptor { chain ->
                            val originalResponse = chain.proceed(chain.request())
                            try {
                                originalResponse.body?.let { body ->
                                    return@addInterceptor originalResponse.newBuilder()
                                        .body(ProgressResponseBody(body, progressListener))
                                        .build()
                                }
                            } catch (ignored: IOException) {
                                println(ignored.localizedMessage)
                            }
                            originalResponse
                        }.build().newCall(request).execute()
                    response.body?.let { body ->
                        val sink: BufferedSink = downloadedFile.sink().buffer()
                        sink.writeAll(body.source())
                        sink.close()

                        if (!verifyHash(downloadedFile, sha256Hash)) {
                            downloadedFile.delete()
                            throw GeneralSecurityException("Hash didn't matched")
                        }
                        result.add(downloadedFile)
                    }
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
            downloadedFile.delete()
            throw GeneralSecurityException("SHA-256 is not supported by device")
        }
        downloadedFile.delete()
        return false
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    inner class ProgressResponseBody(
        private val responseBody: ResponseBody,
        private var listener: (read: Long, total: Long, doneInPercent: Long, taskCompleted: Boolean) -> Unit
    ) : ResponseBody() {
        private val bufferedSource: BufferedSource = source(responseBody.source()).buffer()
        override fun contentType(): MediaType? {
            return responseBody.contentType()
        }

        override fun contentLength(): Long {
            return responseBody.contentLength()
        }

        override fun source(): BufferedSource = bufferedSource

        private fun source(source: Source): Source {
            return object : ForwardingSource(source) {
                var totalBytesRead = 0L

                @Throws(IOException::class)
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    // read() returns the number of bytes read, or -1 if this source is exhausted.
                    totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                    listener.invoke(
                        totalBytesRead,
                        responseBody.contentLength(),
                        (totalBytesRead * 100) / responseBody.contentLength(),
                        bytesRead == -1L
                    )
                    return bytesRead
                }
            }
        }
    }
}

