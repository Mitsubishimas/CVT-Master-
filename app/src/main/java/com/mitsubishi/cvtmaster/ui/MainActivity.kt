package com.mitsubishi.cvtmaster.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var tvDegradation: TextView
    private lateinit var tvPrimaryPressure: TextView
    private lateinit var tvSecondaryPressure: TextView
    private lateinit var tvEngineRpm: TextView
    private lateinit var tvGearRatio: TextView
    private lateinit var tvBeltWear: TextView
    private lateinit var tvTccStatus: TextView
    private lateinit var tvGearPosition: TextView
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
                    l.addView(Button(this).apply { text = "Обучение CVT"; setOnClickListener { Toast.makeText(this@MainActivity, "В разработке", Toast.LENGTH_SHORT).show() } })
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBluetoothEnabled()
            }
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
                            tvConnectionStatus.text = getString(R.string.bt_disconnected)
                            tvConnectionStatus.setTextColor(getColor(R.color.error))
                            btnConnect.text = getString(R.string.bt_connect)
                            stopDataCollection()
                        }
                        ConnectionState.READY -> {
                            tvConnectionStatus.text = getString(R.string.bt_connected)
                            tvConnectionStatus.setTextColor(getColor(R.color.success))
                            btnConnect.text = getString(R.string.bt_disconnect)
                            startDataCollection()
                        }
                        ConnectionState.CONNECTING -> {
                            tvConnectionStatus.text = getString(R.string.bt_connecting)
                            btnConnect.text = "..."
                        }
                        ConnectionState.SCANNING -> tvConnectionStatus.text = getString(R.string.bt_scanning)
                        ConnectionState.ERROR -> {
                            tvConnectionStatus.text = getString(R.string.bt_error)
                            tvConnectionStatus.setTextColor(getColor(R.color.error))
                        }
                        else -> {}
                    }
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
        if (devices.isNotEmpty()) {
            Toast.makeText(this, "Подключение к " + devices[0].name + "...", Toast.LENGTH_SHORT).show()
            bluetoothManager.connectToDevice(devices[0].address)
        } else {
            Toast.makeText(this, "Нет сопряженных устройств. Сопрягите ELM327 в настройках Bluetooth", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnect() { stopDataCollection(); bluetoothManager.disconnect() }

    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val response = bluetoothManager.sendRawCommand("01 00")
                    val data = pidParser.parseELM327Response(response)
                    if (data != null && data.oilTemperature > 0) {
                        dataLogger.addEntry(data)
                        runOnUiThread {
                            try {
                                tvCvtTemp.text = String.format("%.1f\u00b0C", data.oilTemperature)
                                tvDegradation.text = "" + data.oilDegradation
                                tvPrimaryPressure.text = String.format("%.2f MPa", data.primaryPressure)
                                tvSecondaryPressure.text = String.format("%.2f MPa", data.secondaryPressure)
                                tvEngineRpm.text = "" + data.engineRPM + " RPM"
                                tvGearRatio.text = String.format("%.2f", data.gearRatio)
                                tvBeltWear.text = data.beltWearIndex.toString() + "%"
                                tvTccStatus.text = data.torqueConverterLockup.name
                                tvGearPosition.text = data.gearPosition.name
                                graphView.addTemperatureData(data.oilTemperature)
                                graphView.addPressureData(data.primaryPressure)
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {}
                delay(1000)
            }
        }
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
                            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("Ошибки (" + info.size + ")")
                                .setMessage(info.joinToString("\n\n") { it.code + ": " + it.description })
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
            runOnUiThread { Toast.makeText(this@MainActivity, if (f != null) "Сохранено: " + f.name else "Ошибка", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val c = URL(GITHUB_API).openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    c.connectTimeout = 5000; c.readTimeout = 5000
                    c.inputStream.bufferedReader().readText()
                }
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                runOnUiThread {
                    if (tag != "v1.0.6") {
                        val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Обновление " + tag).setMessage("Скачать?")
                            .setPositiveButton("Да") { _, _ -> downloadUpdate(url) }
                            .setNegativeButton("Нет", null).show()
                    } else Toast.makeText(this@MainActivity, "Версия актуальна", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {}
        }
    }

    private fun downloadUpdate(url: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = File(getExternalFilesDir(null), "update.apk")
                    URL(url).openConnection().getInputStream().use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
                    val u = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", f)
                    startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
                }
            } catch (e: Exception) {}
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
