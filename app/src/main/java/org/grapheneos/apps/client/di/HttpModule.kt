package org.grapheneos.apps.client.di

import org.grapheneos.apps.client.item.network.Response
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class HttpModule @Inject constructor
    (
    @Named("file") private val file: File,
    @Named("uri") private val uri: String,
    @Named("timeout") private val timeout: Int? = 60_000,
    @Named("eTag") eTag: String?
) {
    private val connection : HttpURLConnection = URL(uri).openConnection() as HttpURLConnection

    init {
        val range: String = String.format(
            Locale.ENGLISH,
            "bytes=%d-",
            if (file.exists()) file.length() else 0
        )

        connection.readTimeout = timeout ?: 60_000
        connection.connectTimeout = timeout ?: 60_000
        connection.addRequestProperty("Range", range)
        connection.addRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.32 Safari/537.36"
        )
        eTag?.let { tag ->
            connection.addRequestProperty(
                "If-None-Match",
                tag
            )
        }
    }


    fun connect() : Response {
        connection.connect()
        return Response(connection.getHeaderField(""), connection.responseCode)
    }

    fun saveToFile() {
        connection.connect()

        val data = connection.inputStream
        val size = connection.getHeaderField("Content-Length").toLong()

        val bufferSize = maxOf(DEFAULT_BUFFER_SIZE, data.available())
        val out = FileOutputStream(file, file.exists())

        var bytesCopied: Long = 0
        val buffer = ByteArray(bufferSize)
        var bytes = data.read(buffer)
        while (bytes >= 0) {
            out.write(buffer, 0, bytes)
            bytesCopied += bytes
            bytes = data.read(buffer)

            println("Downloaded ${file.length()} out of $size")
        }
    }

}