package com.mitsubishi.cvtmaster.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mitsubishi.cvtmaster.R
import com.mitsubishi.cvtmaster.core.MitsubishiDiagnostic
import com.mitsubishi.cvtmaster.data.DataLogger
import com.mitsubishi.cvtmaster.data.MitsubishiDTC
import com.mitsubishi.cvtmaster.elm327.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 100
        private const val REQUEST_ENABLE_BT = 101
        private const val GITHUB_API = "https://api.github.com/repos/Mitsubishimas/CVT-Master-/releases/latest"
        // Правильные CAN ID для Mitsubishi
        private const val TCM_REQUEST = "7E2"  // Физический адрес TCM (запрос)
        private const val TCM_RESPONSE = "7E9" // Ответ от TCM
        private const val ECM_REQUEST = "7E0"  // ECM запрос
        private const val ECM_RESPONSE = "7E8" // ECM ответ
    }

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var diagnostic: MitsubishiDiagnostic
    private lateinit var dataLogger: DataLogger
    private lateinit var pidParser: JatcoPIDParser
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvCvtTemp: TextView
    private lateinit var tvTempStatus: TextView
    private lateinit var tvDegradation: TextView
    private lateinit var tvPrimaryPressure: TextView
    private lateinit var tvSecondaryPressure: TextView
    private lateinit var tvEngineRpm: TextView
    private lateinit var tvGearRatio: TextView
    private lateinit var tvBeltWear: TextView
    private lateinit var tvTccStatus: TextView
    private lateinit var tvGearPosition: TextView
    private lateinit var tvCvtInfo: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnScanDtc: Button
    private lateinit var btnResetDegradation: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var bottomNav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var contentContainer: LinearLayout
    private lateinit var graphView: CVTGraphView
    private var dataCollectionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        initManagers()
        requestPermissions()
        observeConnectionState()
        checkForUpdates()
    }

    private fun initViews() {
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        tvCvtTemp = findViewById(R.id.tv_cvt_temp)
        tvDegradation = findViewById(R.id.tv_degradation)
        tvPrimaryPressure = findViewById(R.id.tv_primary_pressure)
        tvSecondaryPressure = findViewById(R.id.tv_secondary_pressure)
        tvEngineRpm = findViewById(R.id.tv_engine_rpm)
        tvGearRatio = findViewById(R.id.tv_gear_ratio)
        tvBeltWear = findViewById(R.id.tv_belt_wear)
        tvTccStatus = findViewById(R.id.tv_tcc_status)
        tvGearPosition = findViewById(R.id.tv_gear_position)
        btnConnect = findViewById(R.id.btn_connect)
        btnScanDtc = findViewById(R.id.btn_scan_dtc)
        btnResetDegradation = findViewById(R.id.btn_reset_degradation)
        btnExportLogs = findViewById(R.id.btn_export_logs)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        bottomNav = findViewById(R.id.bottom_nav)
        contentContainer = findViewById(R.id.content_container)
        tvTempStatus = TextView(this).apply { textSize = 14f; setPadding(0,0,0,8) }
        tvCvtInfo = TextView(this).apply { textSize = 11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0,8,0,0) }
        graphView = CVTGraphView(this)
        setupClickListeners()
        setupNavigation()
    }

    private fun initManagers() {
        bluetoothManager = BluetoothManager(this)
        diagnostic = MitsubishiDiagnostic()
        dataLogger = DataLogger(this)
        pidParser = JatcoPIDParser()
    }

    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            if (bluetoothManager.connectionState.value == ConnectionState.READY) disconnect()
            else connectToDevice()
        }
        btnScanDtc.setOnClickListener { scanDTC() }
        btnResetDegradation.setOnClickListener { resetOilDegradation() }
        btnExportLogs.setOnClickListener { exportData() }
        btnCheckUpdate.setOnClickListener { checkForUpdates() }
    }

    private fun setupNavigation() {
        bottomNav.setOnItemSelectedListener { item ->
            contentContainer.removeAllViews()
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    val d = layoutInflater.inflate(R.layout.layout_cvt_dashboard, contentContainer, false)
                    contentContainer.addView(d)
                    tvCvtTemp = d.findViewById(R.id.tv_cvt_temp)
                    tvDegradation = d.findViewById(R.id.tv_degradation)
                    tvPrimaryPressure = d.findViewById(R.id.tv_primary_pressure)
                    tvSecondaryPressure = d.findViewById(R.id.tv_secondary_pressure)
                    tvEngineRpm = d.findViewById(R.id.tv_engine_rpm)
                    tvGearRatio = d.findViewById(R.id.tv_gear_ratio)
                    tvBeltWear = d.findViewById(R.id.tv_belt_wear)
                    tvTccStatus = d.findViewById(R.id.tv_tcc_status)
                    tvGearPosition = d.findViewById(R.id.tv_gear_position)
                    contentContainer.addView(tvTempStatus)
                    contentContainer.addView(tvCvtInfo)
                }
                R.id.nav_graphs -> {
                    graphView = CVTGraphView(this)
                    contentContainer.addView(graphView, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
                }
                R.id.nav_dtc -> {
                    contentContainer.addView(TextView(this).apply {
                        text = "Нажмите Сканировать"
                        setTextColor(getColor(R.color.white)); textSize = 16f; setPadding(32, 32, 32, 32)
                    })
                }
                R.id.nav_service -> {
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
                    l.addView(Button(this).apply { text = "Сброс деградации масла"; setOnClickListener { resetOilDegradation() } })
                    contentContainer.addView(l)
                }
                R.id.nav_logs -> {
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
                    l.addView(TextView(this).apply { text = "Записей: " + dataLogger.getEntryCount(); setTextColor(getColor(R.color.white)) })
                    l.addView(Button(this).apply { text = "Экспорт CSV"; setOnClickListener { exportData() } })
                    contentContainer.addView(l)
                }
            }
            true
        }
    }

    private fun getTempStatus(temp: Float): Triple<String, Int, String> {
        return when {
            temp <= 0 -> Triple("Нет данных", Color.GRAY, "")
            temp < 70 -> Triple("Холодный", Color.CYAN, "Прогрев вариатора")
            temp in 70.0..74.9 -> Triple("Прогрев", Color.rgb(0,200,200), "Выход на рабочую температуру")
            temp in 75.0..97.9 -> Triple("Рабочая температура", Color.rgb(0,255,0), "Оптимальный режим")
            temp in 98.0..105.9 -> Triple("Горячий", Color.rgb(255,165,0), "Повышенная нагрузка")
            else -> Triple("ПЕРЕГРЕВ!", Color.RED, "Остановитесь и дайте остыть!")
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            checkBluetoothEnabled()
        }
    }

    private fun checkBluetoothEnabled() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter != null && !btAdapter.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            Toast.makeText(this, if (resultCode == RESULT_OK) "Bluetooth включен" else "Bluetooth не включен", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeConnectionState() {
        lifecycleScope.launch {
            bluetoothManager.connectionState.collect { state ->
                runOnUiThread {
                    when (state) {
                        ConnectionState.DISCONNECTED -> {
                            tvConnectionStatus.text = "Не подключено"
                            tvConnectionStatus.setTextColor(getColor(R.color.error))
                            btnConnect.text = "Подключить ELM327"
                            stopDataCollection()
                        }
                        ConnectionState.READY -> {
                            tvConnectionStatus.text = "ELM327 подключен"
                            tvConnectionStatus.setTextColor(getColor(R.color.success))
                            btnConnect.text = "Отключить"
                            initELM327ForCar()
                        }
                        ConnectionState.CONNECTING -> {
                            tvConnectionStatus.text = "Подключение..."
                            btnConnect.text = "..."
                        }
                        ConnectionState.SCANNING -> tvConnectionStatus.text = "Поиск..."
                        ConnectionState.ERROR -> {
                            tvConnectionStatus.text = "Ошибка"
                            tvConnectionStatus.setTextColor(getColor(R.color.error))
                            btnConnect.text = "Подключить ELM327"
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun initELM327ForCar() {
        lifecycleScope.launch {
            try {
                runOnUiThread { 
                    tvConnectionStatus.text = "Инициализация..."
                    tvCvtInfo.text = "Настройка ELM327..."
                }
                
                // Сброс
                bluetoothManager.sendRawCommand("ATZ")
                delay(2000)
                // Эхо выкл
                bluetoothManager.sendRawCommand("ATE0")
                delay(200)
                // Linefeed выкл
                bluetoothManager.sendRawCommand("ATL0")
                delay(100)
                // Пробелы вкл
                bluetoothManager.sendRawCommand("ATS1")
                delay(100)
                // Заголовки вкл
                bluetoothManager.sendRawCommand("ATH1")
                delay(200)
                // Автоопределение протокола
                bluetoothManager.sendRawCommand("ATSP0")
                delay(2000)
                
                // Получаем версию ELM327
                val elmVersion = bluetoothManager.sendRawCommand("ATI")
                delay(200)
                
                // Настраиваем физическую адресацию Mitsubishi:
                // Устанавливаем заголовок отправки = TCM физический адрес
                bluetoothManager.sendRawCommand("ATSH $TCM_REQUEST")
                delay(100)
                // Устанавливаем фильтр на ответ от TCM
                bluetoothManager.sendRawCommand("ATCRA $TCM_RESPONSE")
                delay(100)
                // Фильтр CAN ID для приема
                bluetoothManager.sendRawCommand("ATCF $TCM_RESPONSE")
                delay(100)
                
                // Проверяем связь с TCM - запрос поддерживаемых PID
                val tcmResponse = bluetoothManager.sendRawCommand("01 00")
                delay(200)
                
                runOnUiThread {
                    tvConnectionStatus.text = "Подключено к Mitsubishi"
                    tvCvtInfo.text = "ELM: $elmVersion\n" +
                        "TCM запрос: 0x$TCM_REQUEST\n" +
                        "TCM ответ: 0x$TCM_RESPONSE\n" +
                        "ECM запрос: 0x$ECM_REQUEST\n" +
                        "ECM ответ: 0x$ECM_RESPONSE"
                }
                
                startDataCollection()
                
            } catch (e: Exception) {
                runOnUiThread { 
                    tvConnectionStatus.text = "Ошибка инициализации"
                    tvCvtInfo.text = "Ошибка: ${e.message}"
                }
            }
        }
    }

    private fun connectToDevice() {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null) { Toast.makeText(this, "Bluetooth не поддерживается", Toast.LENGTH_LONG).show(); return }
        if (!btAdapter.isEnabled) {
            Toast.makeText(this, "Включите Bluetooth", Toast.LENGTH_SHORT).show()
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BT)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Нет разрешения Bluetooth", Toast.LENGTH_SHORT).show()
                requestPermissions()
                return
            }
        }
        val devices = bluetoothManager.getPairedDevices()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Нет сопряженных устройств", Toast.LENGTH_LONG).show()
            return
        }
        if (devices.size == 1) {
            bluetoothManager.connectToDevice(devices[0].address)
        } else {
            val names = devices.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Выберите ELM327")
                .setItems(names) { _, which -> bluetoothManager.connectToDevice(devices[which].address) }
                .setNegativeButton("Отмена", null).show()
        }
    }

    private fun disconnect() { stopDataCollection(); bluetoothManager.disconnect() }

    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    // Запрос к TCM (0x7E2 -> 0x7E9)
                    val tcmData = bluetoothManager.sendRawCommand("01 00")
                    delay(50)
                    
                    // Температура охлаждающей жидкости (стандартный PID)
                    val tempResp = bluetoothManager.sendRawCommand("01 05")
                    delay(50)
                    
                    // Обороты двигателя
                    val rpmResp = bluetoothManager.sendRawCommand("01 0C")
                    delay(50)
                    
                    // Скорость
                    val speedResp = bluetoothManager.sendRawCommand("01 0D")
                    delay(50)
                    
                    // Дроссельная заслонка
                    val throttleResp = bluetoothManager.sendRawCommand("01 11")
                    
                    val temp = parseTemp(tempResp)
                    val rpm = parseRPM(rpmResp)
                    val speed = parseSpeed(speedResp)
                    val throttle = parseThrottle(throttleResp)
                    
                    if (temp > 0 || rpm > 0) {
                        val data = JatcoCVTData(
                            oilTemperature = temp,
                            engineRPM = rpm,
                            vehicleSpeed = speed,
                            throttlePosition = throttle
                        )
                        dataLogger.addEntry(data)
                        
                        runOnUiThread {
                            try {
                                val (status, color, desc) = getTempStatus(temp)
                                tvCvtTemp.text = String.format("%.1f\u00b0C", temp)
                                tvTempStatus.text = "$status - $desc"
                                tvTempStatus.setTextColor(color)
                                tvEngineRpm.text = "$rpm об/мин"
                                tvGearRatio.text = String.format("%.0f км/ч", speed)
                                tvPrimaryPressure.text = String.format("%.1f%%", throttle)
                                graphView.addTemperatureData(temp)
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    // OBD2 парсеры
    private fun parseTemp(response: String): Float {
        try {
            for (line in response.split("\r", "\n", ">")) {
                val parts = line.trim().split(" ")
                if (parts.size >= 5 && parts[2] == "41" && parts[3] == "05") {
                    return parts[4].toInt(16) - 40f
                }
            }
        } catch (e: Exception) {}
        return 0f
    }

    private fun parseRPM(response: String): Int {
        try {
            for (line in response.split("\r", "\n", ">")) {
                val parts = line.trim().split(" ")
                if (parts.size >= 6 && parts[2] == "41" && parts[3] == "0C") {
                    return ((parts[4].toInt(16) * 256) + parts[5].toInt(16)) / 4
                }
            }
        } catch (e: Exception) {}
        return 0
    }

    private fun parseSpeed(response: String): Float {
        try {
            for (line in response.split("\r", "\n", ">")) {
                val parts = line.trim().split(" ")
                if (parts.size >= 5 && parts[2] == "41" && parts[3] == "0D") {
                    return parts[4].toInt(16).toFloat()
                }
            }
        } catch (e: Exception) {}
        return 0f
    }

    private fun parseThrottle(response: String): Float {
        try {
            for (line in response.split("\r", "\n", ">")) {
                val parts = line.trim().split(" ")
                if (parts.size >= 5 && parts[2] == "41" && parts[3] == "11") {
                    return parts[4].toInt(16) * 100f / 255f
                }
            }
        } catch (e: Exception) {}
        return 0f
    }

    private fun stopDataCollection() { dataCollectionJob?.cancel() }

    private fun scanDTC() {
        lifecycleScope.launch {
            try {
                val r = bluetoothManager.sendRawCommand("03")
                runOnUiThread {
                    val c = parseDTC(r)
                    if (c.isNotEmpty()) {
                        val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                        if (info.isNotEmpty()) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Ошибки (${info.size})")
                                .setMessage(info.joinToString("\n\n") { "${it.code}: ${it.description}\n${it.recommendation}" })
                                .setPositiveButton("OK", null).show()
                        }
                    } else Toast.makeText(this@MainActivity, "Ошибок нет", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {}
        }
    }

    private fun resetOilDegradation() {
        lifecycleScope.launch {
            try {
                val r = OilDegradationReset().resetJF011E(bluetoothManager)
                runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {}
        }
    }

    private fun exportData() {
        lifecycleScope.launch {
            val f = dataLogger.exportToCSV()
            runOnUiThread {
                if (f != null) {
                    Toast.makeText(this@MainActivity, "Сохранено: ${f.absolutePath}", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка сохранения", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val conn = URL(GITHUB_API).openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.setRequestProperty("User-Agent", "CVT-Master")
                    conn.connectTimeout = 10000; conn.readTimeout = 10000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
                    else throw Exception("HTTP ${conn.responseCode}")
                }
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                runOnUiThread {
                    if (tag != "v1.0.17") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Обновление $tag").setMessage("Скачать?")
                            .setPositiveButton("Да") { _, _ -> downloadAndInstall(url) }
                            .setNegativeButton("Нет", null).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun downloadAndInstall(url: String) {
        val progress = AlertDialog.Builder(this@MainActivity).setTitle("Загрузка...").setCancelable(false).create()
        progress.show()
        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "CVT-Master")
                    conn.connectTimeout = 30000; conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    val file = File(getExternalFilesDir(null), "update.apk")
                    conn.inputStream.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                    file
                }
                progress.dismiss()
                val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", apkFile)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) {
                progress.dismiss()
                runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for (l in r.split("\r", "\n", " ")) {
            val h = l.trim()
            if (h.length == 4 && h.all { it in "0123456789ABCDEFabcdef" }) {
                when (h[0].uppercaseChar()) {
                    '0' -> c.add("P0" + h.substring(1))
                    '1' -> c.add("P1" + h.substring(1))
                    '2' -> c.add("P2" + h.substring(1))
                    '3' -> c.add("P3" + h.substring(1))
                }
            }
        }
        return c
    }

    override fun onDestroy() { super.onDestroy(); disconnect() }
}
