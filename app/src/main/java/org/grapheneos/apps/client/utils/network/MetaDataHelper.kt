package org.grapheneos.apps.client.utils.network

import android.content.Context
import android.content.SharedPreferences
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.item.MetaData
import org.grapheneos.apps.client.item.Package
import org.grapheneos.apps.client.item.PackageVariant
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.OutputStream
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

class MetaDataHelper constructor(
    private val okHttpClient: OkHttpClient,
    private val context: Context
) {

    private val version: Int = 0
    private val baseDir = "${context.dataDir.absolutePath}/internet/files/cache/${version}/"
    private val metadata = File("${baseDir}metadata.json")
    private val sign = File("${baseDir}metadata.json.${version}.sig")
    private val pub = File("${baseDir}apps.${version}.pub")

    private val eTagPreferences: SharedPreferences = context.getSharedPreferences(
        "metadata",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val BASE_URL = "https://apps.grapheneos.org"
        private const val TIMESTAMP_KEY = "timestamp"
    }

    @Throws(
        GeneralSecurityException::class,
        DecoderException::class,
        JSONException::class,
        UnknownHostException::class,
        FileNotFoundException::class,
        SSLHandshakeException::class
    )
    fun downloadNdVerifyMetadata(callback: (metadata: MetaData) -> Unit) {

        if (!File(baseDir).exists()) File(baseDir).mkdirs()
        try {
            /*download/validate metadata json, sign and pub files*/
            fetchContent("metadata.json", metadata)
            fetchContent("metadata.json.${version}.sig", sign)
            fetchContent("apps.${version}.pub", pub)
        } catch (e: UnknownHostException) {
            /*
            There is no internet if we still want to continue with cache data
            don't throw this exception and maybe add a flag in
            response that indicate it's cache data
             */
            throw e
        }

        if (!metadata.exists()) {
            throw GeneralSecurityException("file does not exist")
        }
        val message = FileInputStream(metadata).readBytes()

        val signature = FileInputStream(File("${baseDir}metadata.json.${version}.sig"))
            .readBytes()
            .decodeToString()
            .substringAfterLast(".pub")
            .replace("\n", "")
            .toByteArray()

        val pubBytes = FileInputStream(File("${baseDir}apps.${version}.pub"))
            .readBytes()
            .decodeToString()
            .replace("untrusted comment: signify public key", "")
            .replace("\n", "")

        val verified = FileVerifier(pubBytes)
            .verifySignature(
                message,
                signature.decodeToString()
            )

        /*This does not return anything if timestamp verification fails it throw GeneralSecurityException*/
        verifyTimestamp()

        if (verified) {
            val jsonData = JSONObject(message.decodeToString())
            callback.invoke(
                MetaData(
                    jsonData.getLong("time"),
                    jsonData.getJSONObject("apps").toPackages()
                )
            )
            return
        }
        /*verification has been failed. Deleting config related to this version*/
        deleteFiles()
        throw GeneralSecurityException("verification failed")
    }

    private fun JSONObject.toPackages(): List<Package> {
        val result = mutableListOf<Package>()

        keys().forEach { pkgName ->
            val pkg = getJSONObject(pkgName)
            val variants = mutableListOf<PackageVariant>()

            pkg.keys().forEach { variant ->

                val variantData = pkg.getJSONObject(variant)
                val packages = variantData.getJSONArray("packages")
                val hashes = variantData.getJSONArray("hashes")

                if (packages.length() != hashes.length()) {
                    throw GeneralSecurityException("Package hash size miss match")
                }
                val packageInfoMap = mutableMapOf<String, String>()

                for (i in 0 until hashes.length()) {
                    packageInfoMap[packages.getString(i)] = hashes.getString(i)
                }

                variants.add(
                    PackageVariant(
                        pkgName,
                        variant,
                        packageInfoMap,
                        variantData.getInt("versionCode")
                    )
                )
            }
            result.add(Package(pkgName, variants))
        }

        return result
    }

    @Throws(UnknownHostException::class, GeneralSecurityException::class, SecurityException::class)
    private fun fetchContent(pathAfterBaseUrl: String, file: File) {
        val request = Request.Builder()
            .url("${BASE_URL}/${pathAfterBaseUrl}")

        /*Attach previous eTag if there is any*/
        val eTAG = getETag(pathAfterBaseUrl)
        if (file.exists() && eTAG != null) {
            request.header("If-None-Match", eTAG)
        }
        val response = try {
            okHttpClient.newCall(request.build()).execute()
        } catch (e: SecurityException) {
            //user can deny INTERNET permission instead of crashing app let user know it's failed
            throw GeneralSecurityException(e.localizedMessage)
        }

        /*
        * we store ETag from response and attach it on every request
        * so if response code is 304 (unchanged) skip overwriting old file it's still
        * "unchanged" on server
        * */
        if (response.code == 304) {
            return
        }

        if (response.code == 200) {
            response.body?.use { body ->
                val inputStream = body.byteStream()
                val input = BufferedInputStream(inputStream)
                val output: OutputStream = FileOutputStream(file)
                val data = ByteArray(1024)

                var count = 0
                while (count != -1) {
                    count = input.read(data)
                    if (count != -1) {
                        output.write(data, 0, count)
                    }
                }
                output.flush()
                output.close()
                input.close()

                /*save or updated timestamp this will take care of downgrade*/
                verifyTimestamp()

                /*save/update newer eTag if there is any*/
                saveETag(pathAfterBaseUrl, response.headers["ETag"])
            }
        }
    }

    private fun deleteFiles() = File(baseDir).deleteRecursively()

    private fun String.toTimestamp(): Long? {
        return try {
            JSONObject(this).getLong("time")
        } catch (e: JSONException) {
            null
        }
    }

    private fun saveETag(key: String, s: String?) {
        eTagPreferences.edit().putString(key, s).apply()
    }

    private fun getETag(key: String): String? {
        return eTagPreferences.getString(key, null)
    }

    @Throws(GeneralSecurityException::class)
    private fun verifyTimestamp() {
        val timestamp = FileInputStream(metadata).readBytes().decodeToString().toTimestamp()
        val lastTimestamp = eTagPreferences.getLong(TIMESTAMP_KEY, 0L)

        if (timestamp == null) throw GeneralSecurityException("current file timestamp not found!")

        if (lastTimestamp != 0L && lastTimestamp > timestamp) {
            deleteFiles()
            throw GeneralSecurityException("downgrade is not allowed!")
        }
        eTagPreferences.edit().putLong(TIMESTAMP_KEY, timestamp).apply()
    }
}