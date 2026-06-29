package com.mitsubishi.cvtmaster.ui

import android.Manifest
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

class MainActivity : AppCompatActivity() {
    
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
    
    private var dataCollectionJob: Job? = null
    
    companion object {
        private const val PERMISSION_REQUEST = 100
    }
    
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
        
        setupClickListeners()
    }
    
    private fun initManagers() {
        bluetoothManager = BluetoothManager(this)
        diagnostic = MitsubishiDiagnostic()
        dataLogger = DataLogger(this)
        pidParser = JatcoPIDParser()
    }
    
    private fun setupClickListeners() {
        btnConnect.setOnClickListener {
            if (bluetoothManager.connectionState.value == ConnectionState.READY) {
                disconnect()
            } else {
                connectToDevice()
            }
        }
        
        btnScanDtc.setOnClickListener { scanDTC() }
        btnResetDegradation.setOnClickListener { resetOilDegradation() }
        btnExportLogs.setOnClickListener { exportData() }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) 
                != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) 
                != PackageManager.PERMISSION_GRANTED)
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) 
            != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (permissions.isNotEmpty())
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), PERMISSION_REQUEST)
    }
    
    private fun observeConnectionState() {
        lifecycleScope.launch {
            bluetoothManager.connectionState.collect { state ->
                runOnUiThread { updateConnectionUI(state) }
            }
        }
    }
    
    private fun updateConnectionUI(state: ConnectionState) {
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
            ConnectionState.SCANNING -> {
                tvConnectionStatus.text = getString(R.string.bt_scanning)
            }
            ConnectionState.ERROR -> {
                tvConnectionStatus.text = getString(R.string.bt_error)
                tvConnectionStatus.setTextColor(getColor(R.color.error))
            }
            else -> {}
        }
    }
    
    private fun connectToDevice() {
        val devices = bluetoothManager.getPairedDevices()
        if (devices.isNotEmpty()) {
            bluetoothManager.connectToDevice(devices[0].address)
        } else {
            bluetoothManager.startScanning()
        }
    }
    
    private fun disconnect() {
        stopDataCollection()
        bluetoothManager.disconnect()
    }
    
    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = lifecycleScope.launch {
            while (isActive) {
                try {
                    val response = bluetoothManager.sendRawCommand("01 00")
                    val data = pidParser.parseELM327Response(response)
                    
                    if (data != null) {
                        dataLogger.addEntry(data)
                        val health = diagnostic.analyzeCVTHealth(
                            data.oilDegradation, data.oilTemperature,
                            data.primaryPressure, data.secondaryPressure, 0
                        )
                        runOnUiThread { updateDataUI(data, health) }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { /* ignore */ }
                delay(1000)
            }
        }
    }
    
    private fun updateDataUI(data: JatcoCVTData, health: MitsubishiDiagnostic.CVTHealthReport) {
        tvCvtTemp.text = String.format("%.1f°C", data.oilTemperature)
        tvDegradation.text = "${data.oilDegradation}"
        tvPrimaryPressure.text = String.format("%.2f MPa", data.primaryPressure)
        tvSecondaryPressure.text = String.format("%.2f MPa", data.secondaryPressure)
        tvEngineRpm.text = "${data.engineRPM} RPM"
        tvGearRatio.text = String.format("%.2f", data.gearRatio)
        tvBeltWear.text = "${data.beltWearIndex}%"
        tvTccStatus.text = data.torqueConverterLockup.name
        tvGearPosition.text = data.gearPosition.name
        
        // Цвета статусов
        when (health.oilCondition) {
            MitsubishiDiagnostic.OilCondition.CRITICAL -> tvDegradation.setTextColor(getColor(R.color.error))
            MitsubishiDiagnostic.OilCondition.BURNT -> tvDegradation.setTextColor(getColor(R.color.warning))
            else -> tvDegradation.setTextColor(getColor(R.color.white))
        }
    }
    
    private fun stopDataCollection() { dataCollectionJob?.cancel() }
    
    private fun scanDTC() {
        lifecycleScope.launch {
            try {
                val response = bluetoothManager.sendRawCommand("03")
                runOnUiThread {
                    val codes = parseDTCResponse(response)
                    if (codes.isNotEmpty()) showDTCResult(codes)
                    else Toast.makeText(this@MainActivity, "Ошибок нет", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    
    private fun showDTCResult(codes: List<String>) {
        val info = codes.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
        if (info.isNotEmpty()) {
            val msg = info.joinToString("\n\n") { "${it.code}: ${it.description}\n${it.recommendation}" }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Ошибки (${info.size})")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun resetOilDegradation() {
        lifecycleScope.launch {
            try {
                val result = OilDegradationReset().resetJF011E(bluetoothManager)
                runOnUiThread { Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    
    private fun exportData() {
        lifecycleScope.launch {
            val file = dataLogger.exportToCSV()
            runOnUiThread {
                if (file != null) Toast.makeText(this@MainActivity, "Сохранено: ${file.name}", Toast.LENGTH_LONG).show()
                else Toast.makeText(this@MainActivity, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun parseDTCResponse(response: String): List<String> {
        val codes = mutableListOf<String>()
        for (line in response.split("\r", "\n", " ")) {
            val hex = line.trim()
            if (hex.length == 4 && hex.all { it in "0123456789ABCDEFabcdef" }) {
                val code = when (hex[0].uppercaseChar()) {
                    '0' -> "P0${hex.substring(1)}"
                    '1' -> "P1${hex.substring(1)}"
                    '2' -> "P2${hex.substring(1)}"
                    '3' -> "P3${hex.substring(1)}"
                    else -> null
                }
                if (code != null) codes.add(code)
            }
        }
        return codes
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
