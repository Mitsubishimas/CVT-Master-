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
                        text = "Нажмите Сканировать для проверки ошибок"
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
                            tvConnectionStatus.text = "Подключение к ELM327..."
                            btnConnect.text = "..."
                        }
                        ConnectionState.INITIALIZING -> {
                            tvConnectionStatus.text = "Инициализация..."
                        }
                        ConnectionState.SCANNING -> tvConnectionStatus.text = "Поиск ELM327..."
                        ConnectionState.ERROR -> {
                            tvConnectionStatus.text = "Ошибка подключения"
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
                runOnUiThread { tvConnectionStatus.text = "Инициализация ELM327..." }
                
                // Сброс адаптера
                bluetoothManager.sendRawCommand("ATZ")
                delay(1500)
                
                // Выключаем эхо
                bluetoothManager.sendRawCommand("ATE0")
                delay(100)
                
                // Выключаем linefeed
                bluetoothManager.sendRawCommand("ATL0")
                delay(50)
                
                // Включаем пробелы в ответах
                bluetoothManager.sendRawCommand("ATS1")
                delay(50)
                
                // Включаем заголовки CAN
                bluetoothManager.sendRawCommand("ATH1")
                delay(100)
                
                // Устанавливаем протокол ISO 15765-4 CAN 11bit 500kbps
                bluetoothManager.sendRawCommand("ATSP6")
                delay(200)
                
                // Устанавливаем адрес TCM (блок управления трансмиссией)
                bluetoothManager.sendRawCommand("ATSH 7E2")
                delay(100)
                
                // Устанавливаем фильтр CAN ID
                bluetoothManager.sendRawCommand("ATCF 7E2")
                delay(50)
                bluetoothManager.sendRawCommand("ATCRA 7EA")
                delay(50)
                
                runOnUiThread {
                    tvConnectionStatus.text = "Подключено к Mitsubishi"
                    tvCvtInfo.text = "CAN: ISO 15765-4 11bit/500kbps | TCM: 0x7E2"
                }
                
                // Запрашиваем данные авто
                requestCarInfo()
                
                // Запускаем сбор данных CVT
                startDataCollection()
                
            } catch (e: Exception) {
                runOnUiThread {
                    tvConnectionStatus.text = "Ошибка инициализации"
                }
            }
        }
    }

    private fun requestCarInfo() {
        lifecycleScope.launch {
            try {
                // Переключаемся на ECM для запроса VIN
                bluetoothManager.sendRawCommand("ATSH 7E0")
                delay(50)
                bluetoothManager.sendRawCommand("ATFCSH 7E0")
                delay(50)
                
                // Запрашиваем VIN (Mode 09 PID 02)
                val vin = bluetoothManager.sendRawCommand("09 02")
                
                // CAL ID (Mode 09 PID 04)
                val calId = bluetoothManager.sendRawCommand("09 04")
                
                // Возвращаемся к TCM
                bluetoothManager.sendRawCommand("ATSH 7E2")
                delay(50)
                bluetoothManager.sendRawCommand("ATFCSH 7E2")
                
                runOnUiThread {
                    val info = StringBuilder()
                    info.append("=== Mitsubishi CVT Master ===\n")
                    if (vin.isNotBlank() && vin.length > 10) info.append("VIN: $vin\n")
                    if (calId.isNotBlank()) info.append("CAL: $calId\n")
                    info.append("TCM: 0x7E2 | ECM: 0x7E0")
                    tvCvtInfo.text = info.toString()
                }
            } catch (e: Exception) {}
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
            Toast.makeText(this, "Нет сопряженных устройств. Сопрягите ELM327 в настройках Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (devices.size == 1) {
            Toast.makeText(this, "Подключение к " + devices[0].name + "...", Toast.LENGTH_SHORT).show()
            bluetoothManager.connectToDevice(devices[0].address)
        } else {
            val names = devices.map { it.name }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Выберите ELM327")
                .setItems(names) { _, which ->
                    Toast.makeText(this, "Подключение к " + devices[which].name + "...", Toast.LENGTH_SHORT).show()
                    bluetoothManager.connectToDevice(devices[which].address)
                }
                .setNegativeButton("Отмена", null).show()
        }
    }

    private fun disconnect() { stopDataCollection(); bluetoothManager.disconnect() }

    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    // Запрашиваем температуру масла CVT (PID 2105 через Mode 22)
                    val tempResp = bluetoothManager.sendRawCommand("22 2105")
                    delay(30)
                    
                    // Запрашиваем давление первичного вала (PID 2107)
                    val press1Resp = bluetoothManager.sendRawCommand("22 2107")
                    delay(30)
                    
                    // Запрашиваем деградацию масла (PID 210A)
                    val degradResp = bluetoothManager.sendRawCommand("22 210A")
                    delay(30)
                    
                    // Запрашиваем обороты двигателя
                    val rpmResp = bluetoothManager.sendRawCommand("22 210C")
                    delay(30)
                    
                    // Запрашиваем положение селектора
                    val gearResp = bluetoothManager.sendRawCommand("22 2112")
                    
                    // Парсим ответы
                    val temp = parseTempResponse(tempResp)
                    val pressure = parsePressureResponse(press1Resp)
                    val degradation = parseDegradationResponse(degradResp)
                    val rpm = parseRPMResponse(rpmResp)
                    val gear = parseGearResponse(gearResp)
                    
                    if (temp > 0) {
                        runOnUiThread {
                            try {
                                val (status, color, desc) = getTempStatus(temp)
                                tvCvtTemp.text = String.format("%.1f\u00b0C", temp)
                                tvTempStatus.text = "$status - $desc"
                                tvTempStatus.setTextColor(color)
                                tvPrimaryPressure.text = String.format("%.2f MPa", pressure)
                                tvDegradation.text = "$degradation%"
                                tvEngineRpm.text = "$rpm об/мин"
                                tvGearPosition.text = gear
                                graphView.addTemperatureData(temp)
                                graphView.addPressureData(pressure)
                                
                                if (temp >= 106) {
                                    Toast.makeText(this@MainActivity, "ПЕРЕГРЕВ ВАРИАТОРА!", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    // Парсеры ответов ELM327 для Mitsubishi
    private fun parseTempResponse(response: String): Float {
        try {
            // Ответ вида "7EA 03 62 2105 XX"
            val parts = response.split(" ")
            if (parts.size >= 5) {
                val raw = parts[4].toInt(16)
                return (raw * 0.75f) - 40f // Формула Jatco
            }
        } catch (e: Exception) {}
        return 0f
    }
    
    private fun parsePressureResponse(response: String): Float {
        try {
            val parts = response.split(" ")
            if (parts.size >= 6) {
                val raw = ((parts[4].toInt(16) shl 8) or parts[5].toInt(16))
                return raw * 0.01f // MPa
            }
        } catch (e: Exception) {}
        return 0f
    }
    
    private fun parseDegradationResponse(response: String): Int {
        try {
            val parts = response.split(" ")
            if (parts.size >= 5) {
                return parts[4].toInt(16) // 0-255
            }
        } catch (e: Exception) {}
        return 0
    }
    
    private fun parseRPMResponse(response: String): Int {
        try {
            val parts = response.split(" ")
            if (parts.size >= 6) {
                return ((parts[4].toInt(16) shl 8) or parts[5].toInt(16)) / 4
            }
        } catch (e: Exception) {}
        return 0
    }
    
    private fun parseGearResponse(response: String): String {
        try {
            val parts = response.split(" ")
            if (parts.size >= 5) {
                return when (parts[4].toInt(16)) {
                    0 -> "P"
                    1 -> "R"
                    2 -> "N"
                    3 -> "D"
                    4 -> "M"
                    5 -> "S"
                    else -> "?"
                }
            }
        } catch (e: Exception) {}
        return "-"
    }

    private fun stopDataCollection() { dataCollectionJob?.cancel() }

    private fun scanDTC() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Сканирование ошибок...", Toast.LENGTH_SHORT).show()
                val r = bluetoothManager.sendRawCommand("03")
                runOnUiThread {
                    val c = parseDTC(r)
                    if (c.isNotEmpty()) {
                        val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                        if (info.isNotEmpty()) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Найдены ошибки (${info.size})")
                                .setMessage(info.joinToString("\n\n") { "${it.code}: ${it.description}\n👉 ${it.recommendation}" })
                                .setPositiveButton("OK", null).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "Ошибок не найдено ✓", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun resetOilDegradation() {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Сброс счетчика деградации...", Toast.LENGTH_SHORT).show()
                val r = OilDegradationReset().resetJF011E(bluetoothManager)
                runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка сброса", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun exportData() {
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "Экспорт данных...", Toast.LENGTH_SHORT).show()
            val f = dataLogger.exportToCSV()
            runOnUiThread { 
                Toast.makeText(this@MainActivity, 
                    if (f != null) "Сохранено: ${f.name}" else "Ошибка сохранения", 
                    Toast.LENGTH_LONG).show() 
            }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val url = URL(GITHUB_API)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    conn.setRequestProperty("User-Agent", "CVT-Master")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.instanceFollowRedirects = true
                    if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
                    else throw Exception("HTTP ${conn.responseCode}")
                }
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                val downloadUrl = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                runOnUiThread {
                    if (tag != "v1.0.15") {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Доступно обновление $tag")
                            .setMessage("Скачать и установить?")
                            .setPositiveButton("Установить") { _, _ -> downloadAndInstall(downloadUrl) }
                            .setNegativeButton("Позже", null).show()
                    }
                }
            } catch (e: Exception) {}
        }
    }

    private fun downloadAndInstall(url: String) {
        val progress = AlertDialog.Builder(this@MainActivity)
            .setTitle("Загрузка обновления")
            .setMessage("Пожалуйста, подождите...")
            .setCancelable(false)
            .create()
        progress.show()
        
        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "CVT-Master")
                    conn.connectTimeout = 30000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    val file = File(getExternalFilesDir(null), "update.apk")
                    conn.inputStream.use { input -> FileOutputStream(file).use { o -> input.copyTo(o) } }
                    file
                }
                progress.dismiss()
                val apkUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", apkFile)
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
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
