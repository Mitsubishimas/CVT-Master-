package com.mitsubishi.cvtmaster.ui

import android.Manifest
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.mitsubishi.cvtmaster.R
import com.mitsubishi.cvtmaster.data.DataLogger
import com.mitsubishi.cvtmaster.data.MitsubishiDTC
import com.mitsubishi.cvtmaster.elm327.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERM = 100; private const val REQ_BT = 101; private const val REQ_INSTALL = 102
        private const val API = "https://api.github.com/repos/Mitsubishimas/CVT-Master-/releases/latest"
        private const val CURRENT_VERSION = "v1.0.30"
        private const val PREFS_NAME = "cvt_master_prefs"
        private const val KEY_LAST_CHECK = "last_update_check"
        
        // Mitsubishi CVTz50 commands
        private const val MITS_FLOW_CONTROL = "1092"
        private const val MITS_CVT_TYPE = "2111"
        private const val MITS_CVT_DATA = "2110"
        private const val MITS_DTC_READ = "1800FF00"
        private const val MITS_DTC_CLEAR = "14FF00"
        private const val MITS_DETERIORATION_READ = "2110"
        private const val MITS_DETERIORATION_RESET = "3103"
        private const val MITS_SEED_REQUEST = "2701"
        
        private val MITS_PARAMS = arrayOf("2102","2103","2104","2105","2106","2107")
        
        private val OBD_COMMANDS = mapOf(
            "COOLANT_TEMP" to "0105",
            "INTAKE_TEMP" to "010F",
            "ENGINE_RPM" to "010C",
            "VEHICLE_SPEED" to "010D",
            "MAF" to "0110",
            "FUEL_LEVEL" to "012F",
            "MIL_STATUS" to "0101"
        )
    }

    private lateinit var bm: BluetoothManager; private lateinit var logger: DataLogger
    private lateinit var tvStatus: TextView; private lateinit var tvTemp: TextView; private lateinit var tvTempSt: TextView
    private lateinit var tvDeg: TextView; private lateinit var tvPr1: TextView; private lateinit var tvPr2: TextView
    private lateinit var tvRpm: TextView; private lateinit var tvRatio: TextView; private lateinit var tvBelt: TextView
    private lateinit var tvTcc: TextView; private lateinit var tvGear: TextView; private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConn: Button
    private lateinit var nav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var cont: LinearLayout; private lateinit var graph: CVTGraphView
    private var job: Job? = null
    private val logLines = mutableListOf<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    private var downloadId: Long = -1
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    private var cvtModel = "Unknown"
    private var deterioration = 0

    private fun addLog(msg: String) {
        val line = sdf.format(Date()) + " " + msg
        logLines.add(line)
        try { if (logFile != null) FileWriter(logFile, true).use { it.write(line + "\n") } } catch (e: Exception) {}
        runOnUiThread { try { tvLog.text = logLines.takeLast(100).joinToString("\n") } catch (e: Exception) {} }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        addLog("=== CVT Master " + CURRENT_VERSION + " ===")
        addLog("Device: " + Build.MODEL + " | Android: " + Build.VERSION.RELEASE)
        setContentView(R.layout.activity_main)

        val logDir = File(filesDir, "logs"); logDir.mkdirs()
        logFile = File(logDir, "cvt_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt")

        tvStatus = findViewById(R.id.tv_connection_status)
        btnConn = findViewById(R.id.btn_connect)
        nav = findViewById(R.id.bottom_nav); cont = findViewById(R.id.content_container)
        tvTemp = findViewById(R.id.tv_cvt_temp); tvDeg = findViewById(R.id.tv_degradation)
        tvPr1 = findViewById(R.id.tv_primary_pressure); tvPr2 = findViewById(R.id.tv_secondary_pressure)
        tvRpm = findViewById(R.id.tv_engine_rpm); tvRatio = findViewById(R.id.tv_gear_ratio)
        tvBelt = findViewById(R.id.tv_belt_wear); tvTcc = findViewById(R.id.tv_tcc_status); tvGear = findViewById(R.id.tv_gear_position)
        tvTempSt = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, 8) }
        tvInfo = TextView(this).apply { textSize = 11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0, 8, 0, 0) }
        tvLog = TextView(this).apply { textSize = 10f; setTextColor(0xFF00FF00.toInt()); setPadding(8, 8, 8, 8); setTextIsSelectable(true) }
        graph = CVTGraphView(this)
        bm = BluetoothManager(this); logger = DataLogger(this)

        btnConn.setOnClickListener {
            if (bm.connectionState.value == ConnectionState.READY) { stop(); bm.disconnect() }
            else connect()
        }
        // Menu moved to Settings tab
        setupNav()
        reqPerm()
        observe()
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        addLog("onCreate done")
    }

    override fun onResume() {
        super.onResume()
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - lastCheck > 7 * 24 * 60 * 60 * 1000L) checkUpdate(false)
    }

    private fun settingsMenu() {
        val items = arrayOf("Check updates","Copy log","Reset oil (deterioration)","Allow install")
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, w ->
            when (w) { 0->checkUpdate(true); 1->copyLog(); 2->lifecycleScope.launch { resetDeterioration() }; 3->instPerm() }
        }.show()
    }

    private fun copyLog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CVT Log", logLines.joinToString("\n")))
        Toast.makeText(this, "Copied " + logLines.size + " lines", Toast.LENGTH_SHORT).show()
    }

    private fun setupNav() {
        nav.setOnItemSelectedListener { item ->
            cont.removeAllViews()
            when (item.itemId) {
                R.id.nav_dashboard -> {
                    val d = layoutInflater.inflate(R.layout.layout_cvt_dashboard, cont, false)
                    cont.addView(d)
                    tvTemp = d.findViewById(R.id.tv_cvt_temp); tvDeg = d.findViewById(R.id.tv_degradation)
                    tvPr1 = d.findViewById(R.id.tv_primary_pressure); tvPr2 = d.findViewById(R.id.tv_secondary_pressure)
                    tvRpm = d.findViewById(R.id.tv_engine_rpm); tvRatio = d.findViewById(R.id.tv_gear_ratio)
                    tvBelt = d.findViewById(R.id.tv_belt_wear); tvTcc = d.findViewById(R.id.tv_tcc_status); tvGear = d.findViewById(R.id.tv_gear_position)
                    cont.addView(tvTempSt); cont.addView(tvInfo)
                }
                R.id.nav_graphs -> {
                    graph = CVTGraphView(this)
                    cont.addView(graph, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT))
                }
                R.id.nav_dtc -> {
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
                    l.addView(Button(this).apply { text = "Read DTC (1800FF00)"; setOnClickListener { readDTC() } })
                    l.addView(Button(this).apply { text = "Clear DTC (14FF00)"; setOnClickListener { clearDTC() } })
                    l.addView(Button(this).apply { text = "Copy log"; setOnClickListener { copyLog() } })
                    cont.addView(l)
                }
                R.id.nav_settings -> {
                    settingsMenu()
                    val scroll = ScrollView(this)
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                    l.addView(TextView(this).apply { text = "CVT Model: " + cvtModel; setTextColor(getColor(R.color.white)); textSize = 16f; setPadding(0, 0, 0, 8) })
                    l.addView(TextView(this).apply { text = "Deterioration: " + deterioration; setTextColor(if(deterioration>210000)Color.RED else Color.GREEN); textSize = 14f; setPadding(0, 0, 0, 16) })
                    l.addView(Button(this).apply { text = "Detect CVT (2111)"; setOnClickListener { lifecycleScope.launch { detectCVT() } } })
                    l.addView(Button(this).apply { text = "Read Deterioration (2110)"; setOnClickListener { lifecycleScope.launch { readDeterioration() } } })
                    l.addView(Button(this).apply { text = "Reset Deterioration"; setOnClickListener { lifecycleScope.launch { resetDeterioration() } } })
                    l.addView(Button(this).apply { text = "Check updates"; setOnClickListener { checkUpdate(true) } })
                    l.addView(Button(this).apply { text = "Allow install"; setOnClickListener { instPerm() } })
                    l.addView(Button(this).apply { text = "Copy log"; setOnClickListener { copyLog() } })
                    l.addView(Button(this).apply { text = "Reconnect ECU"; setOnClickListener { if(bm.connectionState.value==ConnectionState.READY)initELM() else Toast.makeText(this@MainActivity,"Connect first",Toast.LENGTH_SHORT).show() } })
                    l.addView(TextView(this).apply { text = "LOG:"; setTextColor(0xFF888888.toInt()); textSize = 12f; setPadding(0, 16, 0, 4) })
                    l.addView(tvLog)
                    scroll.addView(l)
                    cont.addView(scroll)
                }
            }
            true
        }
    }

    private fun tStat(t: Float): Triple<String, Int, String> {
        return when { t<=0->Triple("None",Color.GRAY,""); t<70->Triple("Cold",Color.CYAN,"Warmup"); t<75->Triple("Warming",Color.rgb(0,200,200),""); t<98->Triple("OK",Color.rgb(0,255,0),"Optimal"); t<106->Triple("Hot",Color.rgb(255,165,0),"Load"); else->Triple("OVERHEAT!",Color.RED,"Stop!") }
    }

    private fun reqPerm() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (p.isNotEmpty()) ActivityCompat.requestPermissions(this, p.toTypedArray(), PERM)
    }

    override fun onRequestPermissionsResult(c: Int, perms: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(c, perms, g)
        if (c == PERM && g.all { it == PackageManager.PERMISSION_GRANTED }) {
            val bt = BluetoothAdapter.getDefaultAdapter()
            if (bt != null && !bt.isEnabled) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
        }
    }

    private fun instPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle("Install").setMessage("Allow unknown sources?").setPositiveButton("Yes") { _, _ -> startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:$packageName") }, REQ_INSTALL) }.setNegativeButton("No", null).show()
        } else Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        super.onActivityResult(c, r, d)
        if (c == REQ_BT) addLog("BT: " + (if(r==RESULT_OK)"ON" else "OFF"))
    }

    private fun observe() {
        lifecycleScope.launch {
            bm.connectionState.collect { s -> runOnUiThread {
                when (s) {
                    ConnectionState.DISCONNECTED -> { tvStatus.text="Off"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text="Connect"; stop() }
                    ConnectionState.READY -> { tvStatus.text="On"; tvStatus.setTextColor(getColor(R.color.success)); btnConn.text="Disconnect"; initELM() }
                    ConnectionState.CONNECTING -> { tvStatus.text="..."; btnConn.text="..." }
                    ConnectionState.ERROR -> { tvStatus.text="Err"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text="Connect" }
                    else -> {}
                }
            }}
        }
    }

    private fun initELM() = lifecycleScope.launch {
        try {
            runOnUiThread { tvStatus.text = "Init..." }
            addLog("=== MITSUBISHI INIT (CVTz50 protocol) ===")
            
            addLog("[1] ATZ"); bm.sendRawCommand("ATZ"); delay(2000)
            addLog("[2] ATE0"); bm.sendRawCommand("ATE0"); delay(200)
            addLog("[3] ATAL"); bm.sendRawCommand("ATAL"); delay(100)
            addLog("[4] ATST32"); bm.sendRawCommand("ATST32"); delay(100)
            addLog("[5] ATSW00"); bm.sendRawCommand("ATSW00"); delay(100)
            addLog("[6] ATSP6 (CAN 11bit/500kbps)"); bm.sendRawCommand("ATSP6"); delay(500)
            addLog("[7] ATSH7E1 (Mitsubishi CVT header)"); bm.sendRawCommand("ATSH7E1"); delay(100)
            
            val ver = bm.sendRawCommand("ATI"); delay(200); addLog("ELM: " + ver.trim())
            
            // Detect CVT
            detectCVT()
            
            addLog("=== INIT DONE ===")
            startDataLoop()
        } catch (e: Exception) { addLog("Init err: " + e.message) }
    }

    private suspend fun detectCVT() {
        addLog("--- CVT DETECTION ---")
        
        // Step 1: 2101
        addLog("[DETECT] 2101")
        val resp2101 = bm.sendRawCommand("2101"); delay(300)
        addLog("[DETECT] 2101 response: " + resp2101)
        
        // Step 2: 1092 (Mitsubishi flow control)
        addLog("[DETECT] 1092 (Flow Control)")
        bm.sendRawCommand(MITS_FLOW_CONTROL); delay(100)
        
        // Step 3: 2111 (CVT Type)
        addLog("[DETECT] 2111 (CVT Type)")
        val resp2111 = bm.sendRawCommand(MITS_CVT_TYPE); delay(300)
        addLog("[DETECT] 2111 response: " + resp2111)
        
        if (resp2111.contains("6111")) {
            if (resp2111.contains("0250")) { cvtModel = "Outlander 2013"; addLog(">>> OUTLANDER 2013 DETECTED") }
            else if (resp2111.contains("0260")) { cvtModel = "Compass 2007"; addLog(">>> COMPASS 2007 DETECTED") }
            else { cvtModel = "Mitsubishi CVT"; addLog(">>> MITSUBISHI CVT DETECTED") }
        } else {
            addLog("[DETECT] Trying 2110...")
            val resp2110 = bm.sendRawCommand(MITS_CVT_DATA); delay(300)
            addLog("[DETECT] 2110 response: " + resp2110)
            if (resp2110.contains("6110")) {
                cvtModel = "Mitsubishi CVT (generic)"; addLog(">>> GENERIC MITSUBISHI CVT")
            }
        }
        
        runOnUiThread { tvInfo.text = "CVT: " + cvtModel }
        
        // Read deterioration
        readDeterioration()
    }

    private suspend fun readDeterioration() {
        addLog("--- READ DETERIORATION ---")
        addLog("[DET] " + MITS_DETERIORATION_READ)
        val resp = bm.sendRawCommand(MITS_DETERIORATION_READ); delay(300)
        addLog("[DET] Response: " + resp)
        
        // Parse deterioration value (bytes 54-60 for Mitsubishi)
        val clean = resp.replace(Regex("[^0-9A-F:]"), "")
        addLog("[DET] Clean: " + clean + " (len=" + clean.length + ")")
        
        if (clean.length >= 83) {
            try {
                val sub = clean.substring(54, 61)
                deterioration = sub.toInt(16)
                addLog("[DET] Deterioration = " + deterioration)
                runOnUiThread {
                    tvDeg.text = deterioration.toString()
                    tvDeg.setTextColor(if(deterioration > 210000) Color.RED else Color.GREEN)
                }
            } catch (e: Exception) { addLog("[DET] Parse error: " + e.message) }
        }
    }

    private suspend fun resetDeterioration() {
        addLog("--- RESET DETERIORATION ---")
        
        // Step 1: Request seed
        addLog("[RESET] Step 1: " + MITS_SEED_REQUEST)
        val seedResp = bm.sendRawCommand(MITS_SEED_REQUEST); delay(300)
        addLog("[RESET] Seed response: " + seedResp)
        
        if (seedResp.contains("6701")) {
            // Extract seed
            val seed = seedResp.substringAfter("6701").take(8).trim()
            addLog("[RESET] Seed: " + seed)
            
            // Calculate key (simplified - in real CVTz50 uses complex algo)
            val key = seed.reversed()
            addLog("[RESET] Key: " + key)
            
            // Step 2: Send key
            addLog("[RESET] Step 2: 2702" + key)
            val keyResp = bm.sendRawCommand("2702$key"); delay(300)
            addLog("[RESET] Key response: " + keyResp)
            
            if (keyResp.contains("6702")) {
                // Step 3: Reset
                addLog("[RESET] Step 3: " + MITS_DETERIORATION_RESET)
                val resetResp = bm.sendRawCommand(MITS_DETERIORATION_RESET); delay(300)
                addLog("[RESET] Reset response: " + resetResp)
                
                if (resetResp.contains("7103")) {
                    addLog(">>> DETERIORATION RESET SUCCESS")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Deterioration reset OK!", Toast.LENGTH_SHORT).show() }
                    readDeterioration()
                } else {
                    addLog(">>> RESET FAILED")
                    runOnUiThread { Toast.makeText(this@MainActivity, "Reset failed", Toast.LENGTH_SHORT).show() }
                }
            }
        } else {
            addLog(">>> SEED REQUEST FAILED")
            runOnUiThread { Toast.makeText(this@MainActivity, "Seed request failed", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun connect() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) { Toast.makeText(this, "No BT", Toast.LENGTH_LONG).show(); return }
        if (!bt.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { reqPerm(); return }
        val d = bm.getPairedDevices()
        if (d.isEmpty()) { Toast.makeText(this, "No paired devices", Toast.LENGTH_LONG).show(); return }
        if (d.size == 1) bm.connectToDevice(d[0].address)
        else AlertDialog.Builder(this).setTitle("Select ELM327").setItems(d.map{it.name}.toTypedArray()){_,w->bm.connectToDevice(d[w].address)}.setNegativeButton("Cancel",null).show()
    }

    private fun startDataLoop() {
        addLog("=== DATA LOOP ===")
        job?.cancel()
        var cycle = 0
        job = lifecycleScope.launch {
            while (isActive) {
                cycle++; addLog("--- Cycle " + cycle + " ---")
                try {
                    // OBD2: Coolant temp
                    val ct = bm.sendRawCommand(OBD_COMMANDS["COOLANT_TEMP"]!!); delay(50)
                    // OBD2: RPM
                    val rpm = bm.sendRawCommand(OBD_COMMANDS["ENGINE_RPM"]!!); delay(50)
                    // OBD2: Speed
                    val spd = bm.sendRawCommand(OBD_COMMANDS["VEHICLE_SPEED"]!!); delay(50)
                    // CVT: Data
                    val cvt = bm.sendRawCommand(MITS_CVT_DATA); delay(50)
                    
                    addLog("CT: " + ct.take(30) + " | RPM: " + rpm.take(30) + " | SPD: " + spd.take(20) + " | CVT: " + cvt.take(40))
                    
                    val t = parseOBDTemp(ct)
                    val r = parseOBDRPM(rpm)
                    val s = parseOBDSpeed(spd)
                    
                    if (t > 0 || r > 0) {
                        logger.addEntry(JatcoCVTData(oilTemperature = t, engineRPM = r, vehicleSpeed = s))
                        runOnUiThread {
                            try {
                                val (ss, c, d) = tStat(t)
                                tvTemp.text = String.format("%.1f\u00b0C", t); tvTempSt.text = ss + " - " + d; tvTempSt.setTextColor(c)
                                tvRpm.text = r.toString() + " rpm"; tvRatio.text = String.format("%.0f km/h", s)
                                graph.addTemperatureData(t)
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { addLog("Err: " + e.message) }
                delay(1000)
            }
        }
    }

    private fun stop() { job?.cancel() }

    private fun parseOBDTemp(r: String): Float {
        for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "05") return (p[4].toIntOrNull(16)?:0) - 40f }
        return 0f
    }
    private fun parseOBDRPM(r: String): Int {
        for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 6 && p[2] == "41" && p[3] == "0C") return (((p[4].toIntOrNull(16)?:0)*256)+(p[5].toIntOrNull(16)?:0))/4 }
        return 0
    }
    private fun parseOBDSpeed(r: String): Float {
        for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "0D") return (p[4].toIntOrNull(16)?:0).toFloat() }
        return 0f
    }

    private fun readDTC() = lifecycleScope.launch {
        try {
            addLog("[DTC] " + MITS_DTC_READ)
            val r = bm.sendRawCommand(MITS_DTC_READ); delay(300)
            addLog("[DTC] Response: " + r)
            runOnUiThread {
                val c = parseDTC(r)
                if (c.isNotEmpty()) {
                    val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                    if (info.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("DTC (" + info.size + ")").setMessage(info.joinToString("\n\n") { it.code + ": " + it.description }).setPositiveButton("OK", null).show()
                } else Toast.makeText(this@MainActivity, "No errors", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    private fun clearDTC() = lifecycleScope.launch {
        try {
            addLog("[CLEAR] " + MITS_DTC_CLEAR)
            val r = bm.sendRawCommand(MITS_DTC_CLEAR); delay(300)
            addLog("[CLEAR] Response: " + r)
            runOnUiThread { Toast.makeText(this@MainActivity, "DTC cleared", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) {}
    }

    private fun checkUpdate(force: Boolean) = lifecycleScope.launch {
        try {
            if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Checking...", Toast.LENGTH_SHORT).show() }
            val json = withContext(Dispatchers.IO) {
                val c = URL(API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github.v3+json"); c.setRequestProperty("User-Agent", "CVT")
                c.connectTimeout = 10000; c.readTimeout = 10000
                if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else throw Exception("HTTP " + c.responseCode)
            }
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
            val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
            if (tag != CURRENT_VERSION) runOnUiThread { showUpdateDialog(tag, url) }
            else if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Latest version", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Error: " + e.message, Toast.LENGTH_SHORT).show() } }
    }

    private fun showUpdateDialog(tag: String, url: String) {
        AlertDialog.Builder(this).setTitle("Update " + tag).setMessage("Download?").setPositiveButton("Yes") { _, _ -> downloadUpdate(url) }.setNegativeButton("No", null).show()
    }

    private fun downloadUpdate(url: String) {
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("CVT Master Update"); setDescription("Downloading...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CVT-Master-Update.apk")
            })
            Toast.makeText(this, "Download started...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {}
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == downloadId) {
                val dm = ctx?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri != null) startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) })
            }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for (l in r.split("\r", "\n", " ")) { val h = l.trim(); if (h.length == 4 && h.all { it in "0123456789ABCDEFabcdef" }) { when (h[0].uppercaseChar()) { '0' -> c.add("P0" + h.substring(1)); '1' -> c.add("P1" + h.substring(1)); '2' -> c.add("P2" + h.substring(1)); '3' -> c.add("P3" + h.substring(1)) } } }
        return c
    }

    override fun onDestroy() { unregisterReceiver(downloadReceiver); stop(); bm.disconnect(); super.onDestroy() }
}
