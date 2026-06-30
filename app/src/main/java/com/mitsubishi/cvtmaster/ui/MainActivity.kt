package com.mitsubishi.cvtmaster.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.mitsubishi.cvtmaster.R
import com.mitsubishi.cvtmaster.data.DataLogger
import com.mitsubishi.cvtmaster.data.MitsubishiDTC
import com.mitsubishi.cvtmaster.elm327.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERM = 100; private const val REQ_BT = 101; private const val REQ_INSTALL = 102
        private const val API = "https://api.github.com/repos/Mitsubishimas/CVT-Master-/releases/latest"
        private const val TCM_REQ = "7E2"; private const val TCM_RES = "7E9"
    }

    private lateinit var bm: BluetoothManager; private lateinit var logger: DataLogger
    private lateinit var tvStatus: TextView; private lateinit var tvTemp: TextView; private lateinit var tvTempSt: TextView
    private lateinit var tvDeg: TextView; private lateinit var tvPr1: TextView; private lateinit var tvPr2: TextView
    private lateinit var tvRpm: TextView; private lateinit var tvRatio: TextView; private lateinit var tvBelt: TextView
    private lateinit var tvTcc: TextView; private lateinit var tvGear: TextView; private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConn: Button; private lateinit var btnMenu: Button
    private lateinit var nav: com.google.android.material.bottomnavigation.BottomNavigationView
    private lateinit var cont: LinearLayout; private lateinit var graph: CVTGraphView
    private var job: Job? = null; private var auto = false
    private val logLines = mutableListOf<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null

    private fun addLog(msg: String) {
        val line = sdf.format(Date()) + " " + msg
        logLines.add(line)
        try {
            if (logFile != null) {
                FileWriter(logFile, true).use { it.write(line + "\n") }
            }
        } catch (e: Exception) {}
        runOnUiThread {
            try { tvLog.text = logLines.takeLast(50).joinToString("\n") } catch (e: Exception) {}
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        
        logFile = File(getExternalFilesDir(null), "cvt_master_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt")
        addLog("=== CVT Master START ===")
        addLog("Device: " + Build.MODEL + " | Android: " + Build.VERSION.RELEASE)
        addLog("Log file: " + logFile!!.absolutePath)

        tvStatus = findViewById(R.id.tv_connection_status)
        btnConn = findViewById(R.id.btn_connect); btnMenu = findViewById(R.id.btn_menu)
        nav = findViewById(R.id.bottom_nav); cont = findViewById(R.id.content_container)
        tvTemp = findViewById(R.id.tv_cvt_temp); tvDeg = findViewById(R.id.tv_degradation)
        tvPr1 = findViewById(R.id.tv_primary_pressure); tvPr2 = findViewById(R.id.tv_secondary_pressure)
        tvRpm = findViewById(R.id.tv_engine_rpm); tvRatio = findViewById(R.id.tv_gear_ratio)
        tvBelt = findViewById(R.id.tv_belt_wear); tvTcc = findViewById(R.id.tv_tcc_status); tvGear = findViewById(R.id.tv_gear_position)
        tvTempSt = TextView(this).apply { textSize = 14f; setPadding(0, 0, 0, 8) }
        tvInfo = TextView(this).apply { textSize = 11f; setTextColor(0xFFAAAAAA.toInt()); setPadding(0, 8, 0, 0) }
        tvLog = TextView(this).apply { textSize = 10f; setTextColor(0xFF888888.toInt()); setPadding(4, 4, 4, 4); maxLines = 50 }
        graph = CVTGraphView(this)
        bm = BluetoothManager(this); logger = DataLogger(this)
        
        addLog("Views initialized")

        btnConn.setOnClickListener {
            if (bm.connectionState.value == ConnectionState.READY) { addLog("Disconnect requested"); stop(); bm.disconnect() }
            else { addLog("Connect requested"); connect() }
        }
        btnMenu.setOnClickListener { menu() }
        setupNav()
        reqPerm()
        observe()
        addLog("onCreate done")
    }

    private fun menu() {
        addLog("Menu opened")
        val items = arrayOf("Check updates", "Export + Share logs", "Status", "Reset oil", "Allow install", if (auto) "Mode: Auto" else "Mode: ATSP6")
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, w ->
            when (w) {
                0 -> { addLog("Menu: Check updates"); checkUpd() }
                1 -> { addLog("Menu: Export logs"); export() }
                2 -> { addLog("Menu: Status"); info() }
                3 -> { addLog("Menu: Reset oil"); resetOil() }
                4 -> { addLog("Menu: Allow install"); instPerm() }
                5 -> { auto = !auto; addLog("Menu: Protocol = " + (if (auto) "Auto" else "ATSP6")); Toast.makeText(this, "Reconnect to apply", Toast.LENGTH_SHORT).show() }
            }
        }.show()
    }

    private fun info() {
        AlertDialog.Builder(this).setTitle("Status").setMessage(tvInfo.text).setPositiveButton("OK", null).show()
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
                    l.addView(Button(this).apply { text = "Scan DTC"; setOnClickListener { scanDTC() } })
                    cont.addView(l)
                }
                R.id.nav_settings -> {
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
                    l.addView(TextView(this).apply { text = "Settings"; setTextColor(getColor(R.color.white)); textSize = 20f; setPadding(0, 0, 0, 24) })
                    l.addView(TextView(this).apply { text = "CAN Protocol:"; setTextColor(getColor(R.color.white)); textSize = 14f })
                    val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                    val rb1 = RadioButton(this).apply { text = "ATSP6 - Mitsubishi"; setTextColor(getColor(R.color.white)); isChecked = !auto }
                    val rb2 = RadioButton(this).apply { text = "ATSP0 - Auto"; setTextColor(getColor(R.color.white)); isChecked = auto }
                    rg.addView(rb1); rg.addView(rb2); rg.setOnCheckedChangeListener { _, id -> auto = (id == rb2.id) }
                    l.addView(rg)
                    l.addView(android.view.View(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 24) })
                    l.addView(Button(this).apply { text = "Check updates"; setOnClickListener { checkUpd() } })
                    l.addView(Button(this).apply { text = "Export + Share logs"; setOnClickListener { export() } })
                    l.addView(Button(this).apply { text = "Reset oil"; setOnClickListener { resetOil() } })
                    l.addView(Button(this).apply { text = "Status"; setOnClickListener { info() } })
                    l.addView(Button(this).apply { text = "Allow install"; setOnClickListener { instPerm() } })
                    l.addView(Button(this).apply { text = "Reconnect ECU"; setOnClickListener {
                        if (bm.connectionState.value == ConnectionState.READY) { addLog("Reconnect ECU"); initELM() }
                        else Toast.makeText(this@MainActivity, "Connect first", Toast.LENGTH_SHORT).show()
                    }})
                    // Log view
                    l.addView(TextView(this).apply { text = "--- LOG ---"; setTextColor(0xFF888888.toInt()); textSize = 12f; setPadding(0, 24, 0, 4) })
                    l.addView(ScrollView(this).apply { addView(tvLog); layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 300) })
                    cont.addView(l)
                }
            }
            true
        }
    }

    private fun tStat(t: Float): Triple<String, Int, String> {
        return when { t <= 0 -> Triple("None", Color.GRAY, ""); t < 70 -> Triple("Cold", Color.CYAN, "Warmup"); t < 75 -> Triple("Warming", Color.rgb(0, 200, 200), ""); t < 98 -> Triple("OK", Color.rgb(0, 255, 0), "Optimal"); t < 106 -> Triple("Hot", Color.rgb(255, 165, 0), "Load"); else -> Triple("OVERHEAT!", Color.RED, "Stop!") }
    }

    private fun reqPerm() {
        addLog("Checking permissions")
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (p.isNotEmpty()) { addLog("Requesting permissions: " + p.joinToString()); ActivityCompat.requestPermissions(this, p.toTypedArray(), PERM) }
        else addLog("All permissions granted")
    }

    override fun onRequestPermissionsResult(c: Int, perms: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(c, perms, g)
        if (c == PERM) {
            addLog("Permissions result: " + g.joinToString())
            if (g.all { it == PackageManager.PERMISSION_GRANTED }) {
                val bt = BluetoothAdapter.getDefaultAdapter()
                if (bt != null && !bt.isEnabled) { addLog("BT off, requesting enable"); startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT) }
                else addLog("BT already on")
            }
        }
    }

    private fun instPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            addLog("Requesting install permission")
            AlertDialog.Builder(this).setTitle("Install").setMessage("Allow unknown sources?").setPositiveButton("Yes") { _, _ -> startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:$packageName") }, REQ_INSTALL) }.setNegativeButton("No", null).show()
        } else { addLog("Install already allowed"); Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show() }
    }

    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        super.onActivityResult(c, r, d)
        if (c == REQ_BT) addLog("BT enable result: " + (if (r == RESULT_OK) "ON" else "OFF"))
        if (c == REQ_INSTALL) addLog("Install permission result: " + (if (packageManager.canRequestPackageInstalls()) "Granted" else "Denied"))
    }

    private fun observe() {
        addLog("Starting connection observer")
        lifecycleScope.launch {
            bm.connectionState.collect { s -> runOnUiThread {
                when (s) {
                    ConnectionState.DISCONNECTED -> { addLog("State: DISCONNECTED"); tvStatus.text = "Off"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect"; stop() }
                    ConnectionState.READY -> { addLog("State: READY"); tvStatus.text = "On"; tvStatus.setTextColor(getColor(R.color.success)); btnConn.text = "Disconnect"; initELM() }
                    ConnectionState.CONNECTING -> { addLog("State: CONNECTING"); tvStatus.text = "..."; btnConn.text = "..." }
                    ConnectionState.ERROR -> { addLog("State: ERROR"); tvStatus.text = "Err"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect" }
                    else -> { addLog("State: " + s.name) }
                }
            }}
        }
    }

    private fun initELM() {
        addLog("=== INIT ELM327 ===")
        lifecycleScope.launch {
            try {
                runOnUiThread { tvStatus.text = "Init..." }
                
                addLog("Sending: ATZ")
                bm.sendRawCommand("ATZ"); delay(2000)
                addLog("Sending: ATE0")
                bm.sendRawCommand("ATE0"); delay(200)
                addLog("Sending: ATL0")
                bm.sendRawCommand("ATL0"); delay(100)
                addLog("Sending: ATS1")
                bm.sendRawCommand("ATS1"); delay(100)
                addLog("Sending: ATH1")
                bm.sendRawCommand("ATH1"); delay(200)
                
                if (auto) { addLog("Sending: ATSP0 (Auto)"); bm.sendRawCommand("ATSP0"); delay(2000) }
                else { addLog("Sending: ATSP6 (Mitsubishi)"); bm.sendRawCommand("ATSP6"); delay(500) }
                
                addLog("Sending: ATI")
                val ver = bm.sendRawCommand("ATI"); delay(200)
                addLog("ELM Version: " + ver)
                
                addLog("Sending: ATSH " + TCM_REQ)
                bm.sendRawCommand("ATSH $TCM_REQ"); delay(100)
                addLog("Sending: ATCRA " + TCM_RES)
                bm.sendRawCommand("ATCRA $TCM_RES"); delay(100)
                addLog("Sending: ATCF " + TCM_RES)
                bm.sendRawCommand("ATCF $TCM_RES"); delay(100)
                
                addLog("Sending: 01 00 (Test)")
                val test = bm.sendRawCommand("01 00"); delay(300)
                addLog("Test response: " + test)
                
                val ok = test.contains("41")
                runOnUiThread {
                    tvStatus.text = if (ok) "OK" else "No ECU"
                    tvInfo.text = "ELM: " + ver + " | TCM: 0x" + TCM_REQ + "->0x" + TCM_RES + " | " + (if (ok) "ECU OK" else "No response")
                }
                addLog("ECU test: " + (if (ok) "OK" else "FAIL"))
                
                if (ok) start()
            } catch (e: Exception) { addLog("INIT ERROR: " + e.message); runOnUiThread { tvInfo.text = "Error: " + e.message } }
        }
    }

    private fun connect() {
        addLog("=== CONNECT ===")
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) { addLog("BT not supported"); Toast.makeText(this, "No BT", Toast.LENGTH_LONG).show(); return }
        if (!bt.isEnabled) { addLog("BT disabled, requesting enable"); startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            addLog("No BT permission"); Toast.makeText(this, "No perm", Toast.LENGTH_SHORT).show(); reqPerm(); return
        }
        val d = bm.getPairedDevices()
        addLog("Paired devices: " + d.size)
        if (d.isEmpty()) { addLog("No paired devices"); Toast.makeText(this, "No devices", Toast.LENGTH_LONG).show(); return }
        if (d.size == 1) {
            addLog("Connecting to: " + d[0].name + " (" + d[0].address + ")")
            bm.connectToDevice(d[0].address)
        } else {
            AlertDialog.Builder(this).setTitle("Select").setItems(d.map { it.name }.toTypedArray()) { _, w ->
                addLog("Selected: " + d[w].name + " (" + d[w].address + ")")
                bm.connectToDevice(d[w].address)
            }.setNegativeButton("Cancel", null).show()
        }
    }

    private fun start() {
        addLog("=== START DATA COLLECTION ===")
        job?.cancel()
        job = lifecycleScope.launch {
            while (isActive) {
                try {
                    addLog("--- Poll cycle ---")
                    
                    addLog("Sending: 01 05 (Temp)")
                    val r05 = bm.sendRawCommand("01 05"); delay(80)
                    addLog("01 05 response: " + r05)
                    val t = parseT(r05)
                    
                    addLog("Sending: 01 0C (RPM)")
                    val r0C = bm.sendRawCommand("01 0C"); delay(80)
                    addLog("01 0C response: " + r0C)
                    val r = parseR(r0C)
                    
                    addLog("Sending: 01 0D (Speed)")
                    val r0D = bm.sendRawCommand("01 0D"); delay(80)
                    addLog("01 0D response: " + r0D)
                    val s = parseS(r0D)
                    
                    addLog("Sending: 01 11 (Throttle)")
                    val r11 = bm.sendRawCommand("01 11")
                    addLog("01 11 response: " + r11)
                    val th = parseTh(r11)
                    
                    addLog("Parsed: T=" + t + " R=" + r + " S=" + s + " TH=" + th)
                    
                    if (t > 0 || r > 0) {
                        logger.addEntry(JatcoCVTData(oilTemperature = t, engineRPM = r, vehicleSpeed = s, throttlePosition = th))
                        runOnUiThread {
                            try {
                                val (ss, c, d) = tStat(t)
                                tvTemp.text = String.format("%.1f\u00b0C", t); tvTempSt.text = ss + " - " + d; tvTempSt.setTextColor(c)
                                tvRpm.text = r.toString() + " rpm"; tvRatio.text = String.format("%.0f km/h", s)
                                tvPr1.text = String.format("%.1f%%", th); graph.addTemperatureData(t)
                            } catch (e: Exception) {}
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { addLog("Poll error: " + e.message) }
                delay(1000)
            }
        }
    }

    private fun stop() { addLog("=== STOP DATA COLLECTION ==="); job?.cancel() }

    private fun parseT(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "05") return p[4].toInt(16) - 40f }; return 0f }
    private fun parseR(r: String): Int { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 6 && p[2] == "41" && p[3] == "0C") return ((p[4].toInt(16) * 256) + p[5].toInt(16)) / 4 }; return 0 }
    private fun parseS(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "0D") return p[4].toInt(16).toFloat() }; return 0f }
    private fun parseTh(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "11") return p[4].toInt(16) * 100f / 255f }; return 0f }

    private fun scanDTC() {
        addLog("=== SCAN DTC ===")
        lifecycleScope.launch {
            try {
                addLog("Sending: 03")
                val r = bm.sendRawCommand("03")
                addLog("03 response: " + r)
                runOnUiThread {
                    val c = parseDTC(r)
                    addLog("DTC parsed: " + c.size + " codes")
                    if (c.isNotEmpty()) {
                        val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                        if (info.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("DTC (" + info.size + ")").setMessage(info.joinToString("\n\n") { it.code + ": " + it.description }).setPositiveButton("OK", null).show()
                    } else Toast.makeText(this@MainActivity, "No errors", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { addLog("DTC error: " + e.message) }
        }
    }

    private fun resetOil() {
        addLog("=== RESET OIL ===")
        lifecycleScope.launch {
            try { val r = OilDegradationReset().resetJF011E(bm); addLog("Reset result: " + r.message); runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() } }
            catch (e: Exception) { addLog("Reset error: " + e.message) }
        }
    }

    private fun export() {
        addLog("=== EXPORT LOGS ===")
        lifecycleScope.launch {
            try {
                val f = logger.exportToCSV()
                if (f != null) {
                    addLog("CSV saved: " + f.absolutePath)
                    
                    // Also save debug log
                    if (logFile != null && logFile!!.exists()) {
                        addLog("Debug log: " + logFile!!.absolutePath)
                        
                        // Share via Intent
                        val uri = FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", logFile!!)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "CVT Master Debug Log")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runOnUiThread {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Export done")
                                .setMessage("CSV: " + f.name + "\nLog: " + logFile!!.name + "\n\nShare via messenger?")
                                .setPositiveButton("Share") { _, _ -> startActivity(Intent.createChooser(shareIntent, "Share log")) }
                                .setNegativeButton("Close", null).show()
                        }
                    }
                } else addLog("CSV export failed")
            } catch (e: Exception) { addLog("Export error: " + e.message) }
        }
    }

    private fun checkUpd() {
        addLog("=== CHECK UPDATE ===")
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val c = URL(API).openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github.v3+json"); c.setRequestProperty("User-Agent", "CVT")
                    c.connectTimeout = 10000; c.readTimeout = 10000; c.instanceFollowRedirects = true
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else throw Exception("HTTP " + c.responseCode)
                }
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                addLog("Latest: " + tag)
                runOnUiThread { AlertDialog.Builder(this@MainActivity).setTitle("Update " + tag).setMessage("Download?").setPositiveButton("Yes") { _, _ -> download(url) }.setNegativeButton("No", null).show() }
            } catch (e: Exception) { addLog("Update check error: " + e.message) }
        }
    }

    private fun download(url: String) {
        addLog("=== DOWNLOAD: " + url)
        val p = AlertDialog.Builder(this@MainActivity).setTitle("Download...").setCancelable(false).create(); p.show()
        lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.setRequestProperty("User-Agent", "CVT"); c.connectTimeout = 30000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                    val file = File(getExternalFilesDir(null), "update.apk")
                    c.inputStream.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }; file
                }
                p.dismiss(); addLog("Downloaded: " + f.length() + " bytes")
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(FileProvider.getUriForFile(this@MainActivity, packageName + ".fileprovider", f), "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (e: Exception) { p.dismiss(); addLog("Download error: " + e.message); runOnUiThread { Toast.makeText(this@MainActivity, "Error: " + e.message, Toast.LENGTH_LONG).show() } }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for (l in r.split("\r", "\n", " ")) { val h = l.trim(); if (h.length == 4 && h.all { it in "0123456789ABCDEFabcdef" }) { when (h[0].uppercaseChar()) { '0' -> c.add("P0" + h.substring(1)); '1' -> c.add("P1" + h.substring(1)); '2' -> c.add("P2" + h.substring(1)); '3' -> c.add("P3" + h.substring(1)) } } }
        return c
    }

    override fun onDestroy() { addLog("=== DESTROY ==="); super.onDestroy(); stop(); bm.disconnect() }
}
