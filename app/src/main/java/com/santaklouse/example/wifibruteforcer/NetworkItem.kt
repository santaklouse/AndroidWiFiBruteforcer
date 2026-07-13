package com.santaklouse.example.wifibruteforcer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NetworkItem(
    val ssid: String,
    val bssid: String,
    val status: Status,
    val signal: Int,
    val capabilities: String = "",
    val passwordUsed: String? = null
) : Parcelable

enum class Status {
    SUCCESS, TESTING, FAILED, SECURED, OPEN, UNSUPPORTED
}
