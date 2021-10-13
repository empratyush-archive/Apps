package org.grapheneos.apps.client.utils.network

import android.content.Context
import android.content.SharedPreferences
import org.bouncycastle.util.encoders.DecoderException
import org.grapheneos.apps.client.di.DaggerHttpHelperComponent
import org.grapheneos.apps.client.di.HttpHelperComponent.Companion.defaultConfigBuild
import org.grapheneos.apps.client.item.MetaData
import org.grapheneos.apps.client.item.Package
import org.grapheneos.apps.client.item.PackageVariant
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.FileInputStream
import java.io.File
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLHandshakeException

class MetaDataHelper constructor(context: Context) {

    private val metadataFileName = "metadata.json"

    private val version: Int = 0
    private val baseDir = "${context.dataDir.absolutePath}/internet/files/cache/version${version}/"
    private val metadata = File(baseDir,metadataFileName)
    private val sign = File(baseDir, "metadata.json.${version}.sig")
    private val pub = File(baseDir,"apps.${version}.pub")

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
            val metadataETag = fetchContent(metadataFileName, metadata)
            val metadataSignETag = fetchContent("metadata.json.${version}.sig", sign)
            val factoryPubETag =  fetchContent("apps.${version}.pub", pub)


            /*save or updated timestamp this will take care of downgrade*/
            verifyTimestamp()

            /*save/update newer eTag if there is any*/
            saveETag(metadataFileName, metadataETag)
            saveETag("metadata.json.${version}.sig", metadataSignETag)
            saveETag("apps.${version}.pub", factoryPubETag)


        } catch (e: UnknownHostException) {
            /*
            There is no internet if we still want to continue with cache data
            don't throw this exception and maybe add a flag in
            response that indicate it's cache data
             */
            throw e
        } catch (e: SecurityException) {
            //user can deny INTERNET permission instead of crashing app let user know it's failed
            throw GeneralSecurityException(e.localizedMessage)
        }

        if (!metadata.exists()) {
            throw GeneralSecurityException("file does not exist")
        }
        val message = FileInputStream(metadata).readBytes()

        val signature = FileInputStream(sign)
            .readBytes()
            .decodeToString()
            .substringAfterLast(".pub")
            .replace("\n", "")
            .toByteArray()

        val pubBytes = FileInputStream(pub)
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
    private fun fetchContent(pathAfterBaseUrl: String, file: File) : String {

        val caller =DaggerHttpHelperComponent.builder()
            .defaultConfigBuild()
            .uri("${BASE_URL}/${pathAfterBaseUrl}")
            .file(file)
            .apply {
                /*Attach previous eTag if there is any*/
                val eTAG = getETag(pathAfterBaseUrl)
                if (file.exists() && eTAG != null) {
                    addETag(eTAG)
                }
            }
            .build()
            .downloader()

        val response = caller.connect()
        val responseCode = response.resCode

        /*
        * we store ETag from response and attach it on every request
        * so if response code is 304 (unchanged) skip overwriting old file it's still
        * "unchanged" on server
        * */
        if (responseCode == 304) {
            return getETag(pathAfterBaseUrl)!!
        }

        if (responseCode in 200..299) {
            caller.saveToFile()
        }

        return response.eTag ?: ""
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