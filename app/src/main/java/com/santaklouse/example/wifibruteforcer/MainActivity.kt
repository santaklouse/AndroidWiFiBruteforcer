package com.santaklouse.example.wifibruteforcer

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_NETWORKS_UPDATED = "com.santaklouse.example.wifibruteforcer.NETWORKS_UPDATED"
        const val ACTION_LOG_UPDATED = "com.santaklouse.example.wifibruteforcer.LOG_UPDATED"
        const val ACTION_SERVICE_STATE = "com.santaklouse.example.wifibruteforcer.SERVICE_STATE"
        const val EXTRA_NETWORKS = "networks"
        const val EXTRA_LOG = "log"
        const val EXTRA_RUNNING = "running"
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adapter: NetworkAdapter
    private val networks = mutableListOf<NetworkItem>()

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogTitle: TextView
    private lateinit var btnStart: Button
    private var isLogExpanded = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_NETWORKS_UPDATED -> {
                    val newList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(EXTRA_NETWORKS, NetworkItem::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(EXTRA_NETWORKS)
                    }
                    if (newList != null) {
                        networks.clear()
                        networks.addAll(newList)
                        adapter.notifyDataSetChanged()
                    }
                }
                ACTION_LOG_UPDATED -> appendLog(intent.getStringExtra(EXTRA_LOG) ?: return)
                ACTION_SERVICE_STATE -> updateServiceStatus(
                    intent.getBooleanExtra(EXTRA_RUNNING, false)
                )
            }
        }
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (requiredRuntimePermissions().all(::hasPermission)) {
            startWifiService()
        } else {
            Toast.makeText(this, "Нужны разрешения Wi-Fi и точной геолокации", Toast.LENGTH_LONG).show()
        }
    }

    private val provisionProfile = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Toast.makeText(
                this,
                "Рабочий профиль создан. Откройте WiFiBruteforcer со значком портфеля.",
                Toast.LENGTH_LONG
            ).show()
            appendLog("Рабочий профиль создан; продолжите в рабочей копии приложения")
        } else {
            Toast.makeText(this, "Создание рабочего профиля отменено или не поддерживается", Toast.LENGTH_LONG).show()
            appendLog("Provisioning рабочего профиля не завершён")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        devicePolicyManager = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogTitle = findViewById(R.id.tvLogTitle)

        val recycler = findViewById<RecyclerView>(R.id.recyclerNetworks)
        adapter = NetworkAdapter(networks)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnStart = findViewById(R.id.btnStart)
        btnStart.setOnClickListener { checkEnvironmentAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopServiceAction() }
        tvLogTitle.setOnClickListener {
            isLogExpanded = !isLogExpanded
            scrollLogs.visibility = if (isLogExpanded) View.VISIBLE else View.GONE
            tvLogTitle.text = if (isLogExpanded) "▲ Логи сервиса" else "▼ Логи сервиса"
        }

        updateServiceStatus(false)
        updateProfileOwnerUi()
        appendLog("Приложение запущено; список содержит ${LabPasswords.values.size} паролей")
    }

    private fun checkEnvironmentAndStart() {
        if (!devicePolicyManager.isProfileOwnerApp(packageName)) {
            startManagedProfileProvisioning()
            return
        }
        if (!wifiManager.isWifiEnabled) {
            Toast.makeText(this, "Включите Wi-Fi", Toast.LENGTH_LONG).show()
            val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            startActivity(Intent(action))
            return
        }

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
        if (!locationEnabled) {
            Toast.makeText(this, "Включите геолокацию для Wi-Fi-сканирования", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val missing = requiredRuntimePermissions().filterNot(::hasPermission)
        if (missing.isEmpty()) startWifiService() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun startManagedProfileProvisioning() {
        val admin = ComponentName(this, LabDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE).apply {
            putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, false)
            }
        }
        if (intent.resolveActivity(packageManager) == null) {
            Toast.makeText(this, "Прошивка не поддерживает managed work profile", Toast.LENGTH_LONG).show()
            appendLog("Системный обработчик managed-profile provisioning не найден")
            return
        }
        appendLog("Запускаю создание рабочего профиля")
        provisionProfile.launch(intent)
    }

    private fun updateProfileOwnerUi() {
        val isProfileOwner = devicePolicyManager.isProfileOwnerApp(packageName)
        btnStart.text = if (isProfileOwner) {
            "Запустить сканирование"
        } else {
            "Создать рабочий профиль"
        }
        if (!isProfileOwner) {
            tvServiceStatus.text = "Требуется рабочий профиль"
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startWifiService() {
        val intent = Intent(this, WifiScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        tvServiceStatus.text = "Сервис запускается…"
        appendLog("Запуск лабораторной проверки…")
    }

    private fun stopServiceAction() {
        stopService(Intent(this, WifiScanService::class.java))
        updateServiceStatus(false)
        appendLog("Сервис остановлен пользователем")
    }

    private fun updateServiceStatus(running: Boolean) {
        if (running) {
            tvServiceStatus.text = "Сервис работает ✓"
            tvServiceStatus.setBackgroundColor(android.graphics.Color.parseColor("#C8E6C9"))
            tvServiceStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        } else {
            tvServiceStatus.text = "Сервис остановлен"
            tvServiceStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFCCCC"))
            tvServiceStatus.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        tvLogs.append("[$time] $message\n")
        scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onResume() {
        super.onResume()
        updateProfileOwnerUi()
        ContextCompat.registerReceiver(
            this,
            updateReceiver,
            IntentFilter().apply {
                addAction(ACTION_NETWORKS_UPDATED)
                addAction(ACTION_LOG_UPDATED)
                addAction(ACTION_SERVICE_STATE)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(updateReceiver) }
    }
}
