package com.santaklouse.example.wifibruteforcer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
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
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var adapter: NetworkAdapter
    private val allNetworks = mutableListOf<NetworkItem>() // Накопление всех сетей

    private lateinit var tvServiceStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var tvLogTitle: TextView
    private var isLogExpanded = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "UPDATE_NETWORKS" -> {
                    val newList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra("networks", NetworkItem::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra("networks")
                    }
                    newList?.let { fresh ->
                        // Обновляем/добавляем сети (накопление)
                        fresh.forEach { newItem ->
                            val existing = allNetworks.find { it.bssid == newItem.bssid }
                            if (existing != null) {
                                val index = allNetworks.indexOf(existing)
                                allNetworks[index] = newItem
                            } else {
                                allNetworks.add(newItem)
                            }
                        }

                        // Сортируем: успех сверху
                        allNetworks.sortWith(
                            compareByDescending<NetworkItem> { it.status == Status.SUCCESS }
                                .thenByDescending { it.signal }
                        )

                        adapter = NetworkAdapter(allNetworks) // Обновляем адаптер
                        findViewById<RecyclerView>(R.id.recyclerNetworks).adapter = adapter
                    }
                }
                "LOG_UPDATE" -> {
                    val message = intent.getStringExtra("log") ?: return
                    appendLog(message)
                }
            }
        }
    }

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        if (perms.all { it.value }) {
            startWifiService()
        } else {
            Toast.makeText(this, "Нужны разрешения WiFi + Location", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        PasswordManager.savePasswords(this, listOf(
            "12345678",
            "password",
            "qwerty123",
            "MJEE0YLBHRF",
            "1234567890",
            "88888888",
            "11111111",
            "qwertYuiop",
            "0987654321",
            "1234567890",
        ))

        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Инициализация UI
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        tvLogTitle = findViewById(R.id.tvLogTitle)

        val recycler = findViewById<RecyclerView>(R.id.recyclerNetworks)
        adapter = NetworkAdapter(allNetworks)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnStart).setOnClickListener { checkPermissionsAndStart() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopServiceAction() }

        tvLogTitle.setOnClickListener {
            isLogExpanded = !isLogExpanded
            scrollLogs.visibility = if (isLogExpanded) View.VISIBLE else View.GONE
            tvLogTitle.text = if (isLogExpanded) "▲ Логи сервиса" else "▼ Логи сервиса"
        }

        updateServiceStatus(false)
        appendLog("Приложение запущено")
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startWifiService()
        } else {
            requestPermissions.launch(permissions.toTypedArray())
        }
    }

    private fun startWifiService() {
        val intent = Intent(this, WifiScanService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateServiceStatus(true)
        appendLog("Сервис запущен")
        requestIgnoreBatteryOptimization()
    }

    private fun stopServiceAction() {
        stopService(Intent(this, WifiScanService::class.java))
        updateServiceStatus(false)
        appendLog("Сервис остановлен пользователем")
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        if (isRunning) {
            tvServiceStatus.text = "Сервис работает ✓ (сканирование в фоне)"
            tvServiceStatus.setBackgroundColor(android.graphics.Color.parseColor("#C8E6C9"))
            tvServiceStatus.setTextColor(android.graphics.Color.parseColor("#2E7D32"))
        } else {
            tvServiceStatus.text = "Сервис остановлен"
            tvServiceStatus.setBackgroundColor(android.graphics.Color.parseColor("#FFCCCC"))
            tvServiceStatus.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
        }
    }

    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                appendLog("Не удалось открыть настройки батареи")
            }
        }
    }

    fun appendLog(message: String) {
        runOnUiThread {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            tvLogs.append("[$time] $message\n")
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(updateReceiver, IntentFilter().apply {
            addAction("UPDATE_NETWORKS")
            addAction("LOG_UPDATE")
        })
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(updateReceiver) } catch (e: Exception) {}
    }
}