package org.grapheneos.apps.client.item

import org.grapheneos.apps.client.App
import org.grapheneos.apps.client.R

sealed class DownloadCallBack(
    val isSuccessFull: Boolean,
    val genericMsg: String,
    val error: Exception?
) {
    data class Success(val version: Int) : DownloadCallBack(
        true,
        App.getString(R.string.DownloadedSuccessfully),
        null
    )

    data class IoError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfIoError),
        null
    )

    data class SecurityError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfSecurityError),
        e
    )

    data class UnknownHostError(val e: Exception) : DownloadCallBack(
        false,
        App.getString(R.string.dfUnknownHostError),
        e
    )
}