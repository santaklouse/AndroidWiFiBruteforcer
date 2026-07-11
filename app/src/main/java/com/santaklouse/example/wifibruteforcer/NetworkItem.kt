package com.santaklouse.example.wifibruteforcer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class NetworkItem(
    val ssid: String,
    val bssid: String,
    val status: Status,
    val signal: Int,
    val passwordUsed: String? = null   // пароль, который подошёл
) : Parcelable

enum class Status {
    SUCCESS, FAILED, OPEN
}