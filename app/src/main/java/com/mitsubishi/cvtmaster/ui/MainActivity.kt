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
        private const val TCM_REQ = "7E2"; private const val TCM_RES = "7E9"
        private const val CURRENT_VERSION = "v1.0.26"
        private const val PREFS_NAME = "cvt_master_prefs"
        private const val KEY_LAST_CHECK = "last_update_check"
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
    private var downloadId: Long = -1
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private fun addLog(msg: String) {
        val line = sdf.format(Date()) + " " + msg
        logLines.add(line)
        try { if (logFile != null) FileWriter(logFile, true).use { it.write(line + "\n") } } catch (e: Exception) {}
        runOnUiThread { try { tvLog.text = logLines.takeLast(100).joinToString("\n") } catch (e: Exception) {} }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)

        val logDir = File(filesDir, "logs"); logDir.mkdirs()
        logFile = File(logDir, "cvt_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt")
        addLog("=== CVT Master " + CURRENT_VERSION + " ===")
        addLog("Device: " + Build.MODEL + " | Android: " + Build.VERSION.RELEASE)

        tvStatus = findViewById(R.id.tv_connection_status)
        btnConn = findViewById(R.id.btn_connect); btnMenu = findViewById(R.id.btn_menu)
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
            if (bm.connectionState.value == ConnectionState.READY) { addLog("Disconnect"); stop(); bm.disconnect() }
            else { addLog("Connect"); connect() }
        }
        btnMenu.setOnClickListener { menu() }
        setupNav()
        reqPerm()
        observe()
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    override fun onResume() {
        super.onResume()
        checkUpdateAuto()
    }

    private fun checkUpdateAuto() {
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - lastCheck > 7 * 24 * 60 * 60 * 1000L) {
            addLog("Auto check update")
            checkUpdate(false)
        }
    }

    private fun menu() {
        val items = arrayOf(
            "Check updates",
            "Copy log to clipboard",
            "Reset oil degradation",
            "Allow install APK",
            if (auto) "Protocol: Auto" else "Protocol: ATSP6"
        )
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, w ->
            when (w) {
                0 -> checkUpdate(true)
                1 -> copyLog()
                2 -> resetOil()
                3 -> instPerm()
                4 -> { auto = !auto; Toast.makeText(this, "Reconnect to apply", Toast.LENGTH_SHORT).show() }
            }
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
                    l.addView(Button(this).apply { text = "Scan DTC"; setOnClickListener { scanDTC() } })
                    l.addView(Button(this).apply { text = "Copy log"; setOnClickListener { copyLog() } })
                    cont.addView(l)
                }
                R.id.nav_settings -> {
                    val scroll = ScrollView(this)
                    val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                    l.addView(TextView(this).apply { text = "CAN Protocol:"; setTextColor(getColor(R.color.white)); textSize = 14f })
                    val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                    val rb1 = RadioButton(this).apply { text = "ATSP6 - Mitsubishi"; setTextColor(getColor(R.color.white)); isChecked = !auto }
                    val rb2 = RadioButton(this).apply { text = "ATSP0 - Auto"; setTextColor(getColor(R.color.white)); isChecked = auto }
                    rg.addView(rb1); rg.addView(rb2); rg.setOnCheckedChangeListener { _, id -> auto = (id == rb2.id) }
                    l.addView(rg)
                    l.addView(Button(this).apply { text = "Check updates"; setOnClickListener { checkUpdate(true) } })
                    l.addView(Button(this).apply { text = "Reset oil"; setOnClickListener { resetOil() } })
                    l.addView(Button(this).apply { text = "Allow install"; setOnClickListener { instPerm() } })
                    l.addView(Button(this).apply { text = "Copy log"; setOnClickListener { copyLog() } })
                    l.addView(Button(this).apply { text = "Reconnect ECU"; setOnClickListener { if (bm.connectionState.value == ConnectionState.READY) initELM() else Toast.makeText(this@MainActivity, "Connect first", Toast.LENGTH_SHORT).show() } })
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
        return when { t <= 0 -> Triple("None", Color.GRAY, ""); t < 70 -> Triple("Cold", Color.CYAN, "Warmup"); t < 75 -> Triple("Warming", Color.rgb(0, 200, 200), ""); t < 98 -> Triple("OK", Color.rgb(0, 255, 0), "Optimal"); t < 106 -> Triple("Hot", Color.rgb(255, 165, 0), "Load"); else -> Triple("OVERHEAT!", Color.RED, "Stop!") }
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
        if (c == REQ_BT) addLog("BT: " + (if (r == RESULT_OK) "ON" else "OFF"))
    }

    private fun observe() {
        lifecycleScope.launch {
            bm.connectionState.collect { s -> runOnUiThread {
                when (s) {
                    ConnectionState.DISCONNECTED -> { tvStatus.text = "Off"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect"; stop() }
                    ConnectionState.READY -> { tvStatus.text = "On"; tvStatus.setTextColor(getColor(R.color.success)); btnConn.text = "Disconnect"; initELM() }
                    ConnectionState.CONNECTING -> { tvStatus.text = "..."; btnConn.text = "..." }
                    ConnectionState.ERROR -> { tvStatus.text = "Err"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect" }
                    else -> {}
                }
            }}
        }
    }

    private fun initELM() = lifecycleScope.launch {
        try {
            runOnUiThread { tvStatus.text = "Init..." }
            addLog("ATZ"); bm.sendRawCommand("ATZ"); delay(2000)
            addLog("ATE0"); bm.sendRawCommand("ATE0"); delay(200)
            addLog("ATL0"); bm.sendRawCommand("ATL0"); delay(100)
            addLog("ATS1"); bm.sendRawCommand("ATS1"); delay(100)
            addLog("ATH1"); bm.sendRawCommand("ATH1"); delay(200)
            if (auto) { addLog("ATSP0"); bm.sendRawCommand("ATSP0"); delay(2000) } else { addLog("ATSP6"); bm.sendRawCommand("ATSP6"); delay(500) }
            val ver = bm.sendRawCommand("ATI"); delay(200); addLog("ELM: " + ver)
            addLog("ATSH " + TCM_REQ); bm.sendRawCommand("ATSH $TCM_REQ"); delay(100)
            addLog("ATCRA " + TCM_RES); bm.sendRawCommand("ATCRA $TCM_RES"); delay(100)
            addLog("ATCF " + TCM_RES); bm.sendRawCommand("ATCF $TCM_RES"); delay(100)
            val test = bm.sendRawCommand("01 00"); delay(300); addLog("Test: " + test)
            val ok = test.contains("41")
            runOnUiThread { tvStatus.text = if (ok) "OK" else "No ECU"; tvInfo.text = "ELM: " + ver + " | " + (if (ok) "ECU OK" else "No response") }
            if (ok) start()
        } catch (e: Exception) { addLog("Init err: " + e.message) }
    }

    private fun connect() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) return
        if (!bt.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { reqPerm(); return }
        val d = bm.getPairedDevices()
        if (d.isEmpty()) { Toast.makeText(this, "No paired devices", Toast.LENGTH_LONG).show(); return }
        if (d.size == 1) bm.connectToDevice(d[0].address)
        else AlertDialog.Builder(this).setTitle("Select ELM327").setItems(d.map { it.name }.toTypedArray()) { _, w -> bm.connectToDevice(d[w].address) }.setNegativeButton("Cancel", null).show()
    }

    private fun start() {
        job?.cancel()
        job = lifecycleScope.launch {
            while (isActive) {
                try {
                    val t = parseT(bm.sendRawCommand("01 05")); delay(80)
                    val r = parseR(bm.sendRawCommand("01 0C")); delay(80)
                    val s = parseS(bm.sendRawCommand("01 0D")); delay(80)
                    val th = parseTh(bm.sendRawCommand("01 11"))
                    addLog("T=" + t + " R=" + r + " S=" + s + " TH=" + th)
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
                } catch (e: CancellationException) { throw e } catch (e: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stop() { job?.cancel() }

    private fun parseT(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "05") return p[4].toInt(16) - 40f }; return 0f }
    private fun parseR(r: String): Int { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 6 && p[2] == "41" && p[3] == "0C") return ((p[4].toInt(16) * 256) + p[5].toInt(16)) / 4 }; return 0 }
    private fun parseS(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "0D") return p[4].toInt(16).toFloat() }; return 0f }
    private fun parseTh(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "11") return p[4].toInt(16) * 100f / 255f }; return 0f }

    private fun scanDTC() = lifecycleScope.launch {
        try {
            val r = bm.sendRawCommand("03"); addLog("DTC: " + r)
            runOnUiThread {
                val c = parseDTC(r)
                if (c.isNotEmpty()) {
                    val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                    if (info.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("DTC (" + info.size + ")").setMessage(info.joinToString("\n\n") { it.code + ": " + it.description }).setPositiveButton("OK", null).show()
                } else Toast.makeText(this@MainActivity, "No errors", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    private fun resetOil() = lifecycleScope.launch {
        try { val r = OilDegradationReset().resetJF011E(bm); runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() } } catch (e: Exception) {}
    }

    // ==================== ОБНОВЛЕНИЕ ====================

    private fun checkUpdate(force: Boolean) {
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Checking updates...", Toast.LENGTH_SHORT).show()
                val json = withContext(Dispatchers.IO) {
                    val c = URL(API).openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    c.setRequestProperty("User-Agent", "CVT-Master")
                    c.connectTimeout = 10000; c.readTimeout = 10000
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else throw Exception("HTTP " + c.responseCode)
                }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                addLog("Latest GitHub: " + tag + " | Current: " + CURRENT_VERSION)
                
                if (tag != CURRENT_VERSION) {
                    runOnUiThread { showUpdateDialog(tag, url) }
                } else if (force) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "You have the latest version", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                addLog("Update check error: " + e.message)
                if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Error: " + e.message, Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showUpdateDialog(newVersion: String, url: String) {
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("New version: " + newVersion + "\nCurrent: " + CURRENT_VERSION + "\n\nOld version will be removed automatically.")
            .setPositiveButton("Update") { _, _ -> downloadUpdate(url) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadUpdate(url: String) {
        try {
            addLog("Download: " + url)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("CVT Master Update")
                setDescription("Downloading new version...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CVT-Master-Update.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            Toast.makeText(this, "Download started... Check notification bar", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("Download error: " + e.message)
            Toast.makeText(this, "Error: " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == downloadId) {
                addLog("Download complete: " + id)
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(downloadId)
                if (uri != null) {
                    addLog("Installing: " + uri)
                    val installIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                }
            }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for (l in r.split("\r", "\n", " ")) { val h = l.trim(); if (h.length == 4 && h.all { it in "0123456789ABCDEFabcdef" }) { when (h[0].uppercaseChar()) { '0' -> c.add("P0" + h.substring(1)); '1' -> c.add("P1" + h.substring(1)); '2' -> c.add("P2" + h.substring(1)); '3' -> c.add("P3" + h.substring(1)) } } }
        return c
    }

    override fun onDestroy() {
        unregisterReceiver(downloadReceiver)
        super.onDestroy()
        stop()
        bm.disconnect()
    }
}
