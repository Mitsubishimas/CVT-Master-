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
        private const val CURRENT_VERSION = "v1.0.27"
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
        addLog("┌── onCreate START")
        addLog("│ SDK: " + Build.VERSION.SDK_INT + " | Release: " + Build.VERSION.RELEASE)
        addLog("│ Model: " + Build.MODEL + " | Manufacturer: " + Build.MANUFACTURER)
        
        setContentView(R.layout.activity_main)
        addLog("│ setContentView = OK")

        val logDir = File(filesDir, "logs")
        val dirOk = logDir.mkdirs()
        addLog("│ logDir: " + logDir.absolutePath + " (created=" + dirOk + ")")
        logFile = File(logDir, "cvt_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt")
        addLog("│ logFile: " + logFile!!.name)

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
        addLog("│ findViewById all views = OK")

        addLog("│ init BluetoothManager...")
        bm = BluetoothManager(this)
        addLog("│ init DataLogger...")
        logger = DataLogger(this)
        addLog("│ managers init = OK")

        btnConn.setOnClickListener {
            addLog("├── [BTN] Connect clicked")
            if (bm.connectionState.value == ConnectionState.READY) {
                addLog("│   State=READY -> calling disconnect()")
                stop()
                bm.disconnect()
            } else {
                addLog("│   State!=READY -> calling connect()")
                connect()
            }
        }
        btnMenu.setOnClickListener {
            addLog("├── [BTN] Menu clicked")
            menu()
        }

        addLog("│ calling setupNav()...")
        setupNav()
        addLog("│ calling reqPerm()...")
        reqPerm()
        addLog("│ calling observe()...")
        observe()
        addLog("│ register download receiver...")
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        addLog("└── onCreate DONE")
    }

    override fun onResume() {
        super.onResume()
        addLog("┌── onResume")
        val lastCheck = prefs.getLong(KEY_LAST_CHECK, 0)
        val elapsed = System.currentTimeMillis() - lastCheck
        addLog("│ Last update check: " + (if (lastCheck == 0L) "NEVER" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastCheck))))
        addLog("│ Elapsed: " + (elapsed / 3600000) + " hours")
        if (elapsed > 7 * 24 * 60 * 60 * 1000L) {
            addLog("│ > 7 days -> auto check update")
            checkUpdate(false)
        } else {
            addLog("│ < 7 days -> skip auto check")
        }
        addLog("└── onResume DONE")
    }

    private fun menu() {
        addLog("├── menu() showing dialog")
        val items = arrayOf("Check updates","Copy log to clipboard","Reset oil degradation","Allow install APK",if(auto)"Protocol: Auto" else "Protocol: ATSP6")
        AlertDialog.Builder(this).setTitle("Menu").setItems(items) { _, w ->
            addLog("│   menu selected: " + items[w])
            when (w) { 0->checkUpdate(true); 1->copyLog(); 2->resetOil(); 3->instPerm(); 4->{auto=!auto; addLog("│   Protocol toggled: " + (if(auto)"Auto" else "ATSP6")); Toast.makeText(this,"Reconnect to apply",Toast.LENGTH_SHORT).show()} }
        }.show()
    }

    private fun copyLog() {
        addLog("├── copyLog()")
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = logLines.joinToString("\n")
        clipboard.setPrimaryClip(ClipData.newPlainText("CVT Log", text))
        addLog("│   Copied " + logLines.size + " lines (" + text.length + " chars)")
        Toast.makeText(this, "Copied " + logLines.size + " lines", Toast.LENGTH_SHORT).show()
    }

    private fun setupNav() {
        addLog("├── setupNav()")
        nav.setOnItemSelectedListener { item ->
            cont.removeAllViews()
            val tabName = when(item.itemId) { R.id.nav_dashboard->"Dashboard"; R.id.nav_graphs->"Graphs"; R.id.nav_dtc->"DTC"; R.id.nav_settings->"Settings"; else->"Unknown" }
            addLog("│   Tab selected: " + tabName)
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
                    l.addView(Button(this).apply { text = "Scan DTC"; setOnClickListener { addLog("├── [BTN] Scan DTC"); scanDTC() } })
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
                    l.addView(Button(this).apply { text = "Check updates"; setOnClickListener { addLog("├── [BTN] Check updates"); checkUpdate(true) } })
                    l.addView(Button(this).apply { text = "Reset oil"; setOnClickListener { addLog("├── [BTN] Reset oil"); resetOil() } })
                    l.addView(Button(this).apply { text = "Allow install"; setOnClickListener { addLog("├── [BTN] Allow install"); instPerm() } })
                    l.addView(Button(this).apply { text = "Copy log"; setOnClickListener { copyLog() } })
                    l.addView(Button(this).apply { text = "Reconnect ECU"; setOnClickListener { addLog("├── [BTN] Reconnect ECU"); if(bm.connectionState.value==ConnectionState.READY)initELM() else Toast.makeText(this@MainActivity,"Connect first",Toast.LENGTH_SHORT).show() } })
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
        addLog("├── reqPerm()")
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val btConn = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            val btScan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            addLog("│   BLUETOOTH_CONNECT: " + (if(btConn==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
            addLog("│   BLUETOOTH_SCAN: " + (if(btScan==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
            if (btConn != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (btScan != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            val bt = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
            addLog("│   BLUETOOTH: " + (if(bt==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
            if (bt != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH)
        }
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        addLog("│   ACCESS_FINE_LOCATION: " + (if(loc==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
        if (loc != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        
        if (p.isNotEmpty()) {
            addLog("│   Requesting " + p.size + " permissions: " + p.joinToString { it.substringAfterLast(".") })
            ActivityCompat.requestPermissions(this, p.toTypedArray(), PERM)
        } else {
            addLog("│   All permissions already GRANTED")
        }
    }

    override fun onRequestPermissionsResult(c: Int, perms: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(c, perms, g)
        addLog("├── onRequestPermissionsResult code=" + c)
        if (c == PERM) {
            for (i in perms.indices) {
                addLog("│   " + perms[i].substringAfterLast(".") + ": " + (if(g[i]==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
            }
            if (g.all { it == PackageManager.PERMISSION_GRANTED }) {
                addLog("│   All granted -> checkBluetoothEnabled()")
                val bt = BluetoothAdapter.getDefaultAdapter()
                if (bt != null && !bt.isEnabled) {
                    addLog("│   BT is OFF -> requesting enable")
                    startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
                } else {
                    addLog("│   BT is ON")
                }
            }
        }
    }

    private fun instPerm() {
        addLog("├── instPerm()")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canInstall = packageManager.canRequestPackageInstalls()
            addLog("│   canRequestPackageInstalls: " + canInstall)
            if (!canInstall) {
                addLog("│   Showing dialog to allow unknown sources")
                AlertDialog.Builder(this).setTitle("Install").setMessage("Allow unknown sources?")
                    .setPositiveButton("Yes") { _, _ ->
                        addLog("│   User clicked YES -> opening settings")
                        startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply { data = Uri.parse("package:$packageName") }, REQ_INSTALL)
                    }
                    .setNegativeButton("No") { addLog("│   User clicked NO") }
                    .show()
            } else {
                addLog("│   Already allowed")
                Toast.makeText(this, "Already allowed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(c: Int, r: Int, d: Intent?) {
        super.onActivityResult(c, r, d)
        addLog("├── onActivityResult request=" + c + " result=" + r + " (" + (if(r==RESULT_OK)"OK" else "CANCELLED") + ")")
        if (c == REQ_BT) addLog("│   BT enable result: " + (if(r==RESULT_OK)"User enabled BT" else "User cancelled"))
    }

    private fun observe() {
        addLog("├── observe() - starting connection state collector")
        lifecycleScope.launch {
            bm.connectionState.collect { s ->
                addLog("│   ConnectionState changed: " + s.name)
                runOnUiThread {
                    when (s) {
                        ConnectionState.DISCONNECTED -> {
                            addLog("│   -> DISCONNECTED: stopping data collection")
                            tvStatus.text = "Off"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect"; stop()
                        }
                        ConnectionState.READY -> {
                            addLog("│   -> READY: BT connected, calling initELM()")
                            tvStatus.text = "On"; tvStatus.setTextColor(getColor(R.color.success)); btnConn.text = "Disconnect"; initELM()
                        }
                        ConnectionState.CONNECTING -> {
                            addLog("│   -> CONNECTING: waiting for BT socket")
                            tvStatus.text = "..."; btnConn.text = "..."
                        }
                        ConnectionState.ERROR -> {
                            addLog("│   -> ERROR: connection failed")
                            tvStatus.text = "Err"; tvStatus.setTextColor(getColor(R.color.error)); btnConn.text = "Connect"
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    private fun initELM() {
        addLog("┌── initELM() - ELM327 initialization sequence")
        lifecycleScope.launch {
            try {
                runOnUiThread { tvStatus.text = "Init..." }
                
                addLog("│ [1/10] ATZ - reset adapter")
                var resp = bm.sendRawCommand("ATZ"); delay(2000)
                addLog("│   <- " + resp.take(50))
                
                addLog("│ [2/10] ATE0 - echo off")
                resp = bm.sendRawCommand("ATE0"); delay(200)
                addLog("│   <- " + resp)
                
                addLog("│ [3/10] ATL0 - linefeed off")
                resp = bm.sendRawCommand("ATL0"); delay(100)
                addLog("│   <- " + resp)
                
                addLog("│ [4/10] ATS1 - spaces on")
                resp = bm.sendRawCommand("ATS1"); delay(100)
                addLog("│   <- " + resp)
                
                addLog("│ [5/10] ATH1 - headers on")
                resp = bm.sendRawCommand("ATH1"); delay(200)
                addLog("│   <- " + resp)
                
                addLog("│ [6/10] Protocol: " + (if(auto) "ATSP0 (Auto)" else "ATSP6 (Mitsubishi CAN 11bit/500kbps)"))
                resp = if (auto) { bm.sendRawCommand("ATSP0"); delay(2000); "ATSP0 sent" } else { bm.sendRawCommand("ATSP6"); delay(500); "ATSP6 sent" }
                addLog("│   <- " + resp)
                
                addLog("│ [7/10] ATI - get ELM version")
                val ver = bm.sendRawCommand("ATI"); delay(200)
                addLog("│   <- " + ver.trim())
                
                addLog("│ [8/10] ATSH " + TCM_REQ + " - set TCM request header")
                resp = bm.sendRawCommand("ATSH $TCM_REQ"); delay(100)
                addLog("│   <- " + resp)
                
                addLog("│ [9/10] ATCRA " + TCM_RES + " - set TCM response address")
                resp = bm.sendRawCommand("ATCRA $TCM_RES"); delay(100)
                addLog("│   <- " + resp)
                
                addLog("│ [10/10] ATCF " + TCM_RES + " - set CAN filter")
                resp = bm.sendRawCommand("ATCF $TCM_RES"); delay(100)
                addLog("│   <- " + resp)
                
                addLog("│ --- TEST: 01 00 (supported PIDs) ---")
                val test = bm.sendRawCommand("01 00"); delay(300)
                addLog("│   <- RAW: " + test)
                val ok = test.contains("41")
                addLog("│   ECU response: " + (if(ok) "OK (found '41')" else "NO RESPONSE (no '41' in reply)"))
                
                runOnUiThread {
                    tvStatus.text = if (ok) "OK" else "No ECU"
                    tvInfo.text = "ELM: " + ver.trim() + " | " + (if(ok) "ECU OK" else "No response")
                }
                
                if (ok) {
                    addLog("│   -> Starting data collection")
                    start()
                } else {
                    addLog("│   -> Skipping data collection (no ECU)")
                }
                addLog("└── initELM() DONE")
            } catch (e: Exception) {
                addLog("│   ⚠ EXCEPTION: " + e.message)
                addLog("│   Stack: " + (e.stackTraceToString().take(200)))
            }
        }
    }

    private fun connect() {
        addLog("┌── connect()")
        val bt = BluetoothAdapter.getDefaultAdapter()
        addLog("│   BluetoothAdapter: " + (if(bt==null) "NULL" else bt!!.address))
        
        if (bt == null) {
            addLog("│   ⚠ No BT adapter -> return")
            Toast.makeText(this, "No Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        
        addLog("│   BT enabled: " + bt.isEnabled)
        if (!bt.isEnabled) {
            addLog("│   -> requesting BT enable")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val perm = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            addLog("│   BLUETOOTH_CONNECT: " + (if(perm==PackageManager.PERMISSION_GRANTED)"GRANTED" else "DENIED"))
            if (perm != PackageManager.PERMISSION_GRANTED) {
                addLog("│   ⚠ No permission -> reqPerm()")
                reqPerm()
                return
            }
        }
        
        val d = bm.getPairedDevices()
        addLog("│   Paired devices: " + d.size)
        for (dev in d) {
            addLog("│     - " + dev.name + " (" + dev.address + ")")
        }
        
        if (d.isEmpty()) {
            addLog("│   ⚠ No paired devices -> return")
            Toast.makeText(this, "No paired devices", Toast.LENGTH_LONG).show()
            return
        }
        
        if (d.size == 1) {
            addLog("│   Single device -> connecting to: " + d[0].name)
            bm.connectToDevice(d[0].address)
        } else {
            addLog("│   Multiple devices -> showing selection dialog")
            AlertDialog.Builder(this).setTitle("Select ELM327")
                .setItems(d.map { it.name }.toTypedArray()) { _, w ->
                    addLog("│   User selected: " + d[w].name)
                    bm.connectToDevice(d[w].address)
                }
                .setNegativeButton("Cancel", null).show()
        }
        addLog("└── connect() DONE")
    }

    private fun start() {
        addLog("┌── start() - beginning data collection loop")
        job?.cancel()
        var cycle = 0
        job = lifecycleScope.launch {
            while (isActive) {
                cycle++
                addLog("│   --- Cycle " + cycle + " ---")
                try {
                    addLog("│   [req] 01 05 (Coolant temp)")
                    val r05 = bm.sendRawCommand("01 05"); delay(80)
                    addLog("│   [res] 01 05 <- " + r05)
                    val t = parseT(r05)
                    
                    addLog("│   [req] 01 0C (RPM)")
                    val r0C = bm.sendRawCommand("01 0C"); delay(80)
                    addLog("│   [res] 01 0C <- " + r0C)
                    val r = parseR(r0C)
                    
                    addLog("│   [req] 01 0D (Speed)")
                    val r0D = bm.sendRawCommand("01 0D"); delay(80)
                    addLog("│   [res] 01 0D <- " + r0D)
                    val s = parseS(r0D)
                    
                    addLog("│   [req] 01 11 (Throttle)")
                    val r11 = bm.sendRawCommand("01 11")
                    addLog("│   [res] 01 11 <- " + r11)
                    val th = parseTh(r11)
                    
                    addLog("│   Parsed: T=" + t + "°C R=" + r + "rpm S=" + s + "km/h TH=" + th + "%")
                    
                    if (t > 0 || r > 0) {
                        addLog("│   -> Valid data, updating UI")
                        logger.addEntry(JatcoCVTData(oilTemperature = t, engineRPM = r, vehicleSpeed = s, throttlePosition = th))
                        runOnUiThread {
                            try {
                                val (ss, c, d) = tStat(t)
                                tvTemp.text = String.format("%.1f\u00b0C", t); tvTempSt.text = ss + " - " + d; tvTempSt.setTextColor(c)
                                tvRpm.text = r.toString() + " rpm"; tvRatio.text = String.format("%.0f km/h", s)
                                tvPr1.text = String.format("%.1f%%", th); graph.addTemperatureData(t)
                            } catch (e: Exception) {
                                addLog("│   UI update error: " + e.message)
                            }
                        }
                    } else {
                        addLog("│   -> No valid data (all zeros), skipping UI update")
                    }
                } catch (e: CancellationException) {
                    addLog("│   Job cancelled")
                    throw e
                } catch (e: Exception) {
                    addLog("│   ⚠ Poll error: " + e.message)
                }
                delay(1000)
            }
        }
    }

    private fun stop() {
        addLog("├── stop() - cancelling data collection")
        job?.cancel()
        addLog("│   job cancelled")
    }

    private fun parseT(r: String): Float {
        for (l in r.split("\r", "\n", ">")) {
            val p = l.trim().split(" ")
            if (p.size >= 5 && p[2] == "41" && p[3] == "05") {
                val raw = p[4].toInt(16)
                val valv = raw - 40f
                return valv
            }
        }
        return 0f
    }
    private fun parseR(r: String): Int { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 6 && p[2] == "41" && p[3] == "0C") return ((p[4].toInt(16) * 256) + p[5].toInt(16)) / 4 }; return 0 }
    private fun parseS(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "0D") return p[4].toInt(16).toFloat() }; return 0f }
    private fun parseTh(r: String): Float { for (l in r.split("\r", "\n", ">")) { val p = l.trim().split(" "); if (p.size >= 5 && p[2] == "41" && p[3] == "11") return p[4].toInt(16) * 100f / 255f }; return 0f }

    private fun scanDTC() {
        addLog("┌── scanDTC()")
        lifecycleScope.launch {
            try {
                addLog("│   [req] 03 (DTC)")
                val r = bm.sendRawCommand("03")
                addLog("│   [res] 03 <- " + r)
                runOnUiThread {
                    val c = parseDTC(r)
                    addLog("│   Parsed " + c.size + " codes: " + c.joinToString())
                    if (c.isNotEmpty()) {
                        val info = c.mapNotNull { MitsubishiDTC.getDTCInfo(it) }
                        addLog("│   Known DTC: " + info.size)
                        if (info.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("DTC (" + info.size + ")").setMessage(info.joinToString("\n\n") { it.code + ": " + it.description }).setPositiveButton("OK", null).show()
                    } else Toast.makeText(this@MainActivity, "No errors", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { addLog("│   ⚠ DTC error: " + e.message) }
        }
    }

    private fun resetOil() {
        addLog("┌── resetOil()")
        lifecycleScope.launch {
            try {
                addLog("│   Calling OilDegradationReset.resetJF011E()")
                val r = OilDegradationReset().resetJF011E(bm)
                addLog("│   Result: " + r.success + " - " + r.message)
                runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) { addLog("│   ⚠ Reset error: " + e.message) }
        }
    }

    // ==================== ОБНОВЛЕНИЕ ====================

    private fun checkUpdate(force: Boolean) {
        addLog("┌── checkUpdate(force=" + force + ")")
        lifecycleScope.launch {
            try {
                Toast.makeText(this@MainActivity, "Checking updates...", Toast.LENGTH_SHORT).show()
                addLog("│   GET " + API)
                val json = withContext(Dispatchers.IO) {
                    val c = URL(API).openConnection() as HttpURLConnection
                    c.setRequestProperty("Accept", "application/vnd.github.v3+json")
                    c.setRequestProperty("User-Agent", "CVT-Master")
                    c.connectTimeout = 10000; c.readTimeout = 10000
                    addLog("│   HTTP response code: " + c.responseCode)
                    if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else throw Exception("HTTP " + c.responseCode)
                }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
                val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
                addLog("│   GitHub tag: " + tag)
                addLog("│   Current version: " + CURRENT_VERSION)
                addLog("│   Download URL: " + url.take(80) + "...")
                
                if (tag != CURRENT_VERSION) {
                    addLog("│   -> New version available!")
                    runOnUiThread { showUpdateDialog(tag, url) }
                } else {
                    addLog("│   -> Already latest version")
                    if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Latest version installed", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                addLog("│   ⚠ Update check error: " + e.message)
                if (force) runOnUiThread { Toast.makeText(this@MainActivity, "Error: " + e.message, Toast.LENGTH_SHORT).show() }
            }
            addLog("└── checkUpdate() DONE")
        }
    }

    private fun showUpdateDialog(newVersion: String, url: String) {
        addLog("├── showUpdateDialog(" + newVersion + ")")
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("New: " + newVersion + "\nCurrent: " + CURRENT_VERSION + "\n\nOld version will be removed.")
            .setPositiveButton("Update") { _, _ ->
                addLog("│   User clicked UPDATE")
                downloadUpdate(url)
            }
            .setNegativeButton("Later") { _, _ -> addLog("│   User clicked LATER") }
            .show()
    }

    private fun downloadUpdate(url: String) {
        addLog("┌── downloadUpdate()")
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("CVT Master Update")
                setDescription("Downloading...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CVT-Master-Update.apk")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            addLog("│   DownloadManager enqueued. ID=" + downloadId)
            addLog("│   File: Downloads/CVT-Master-Update.apk")
            Toast.makeText(this, "Download started... Check notification bar", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            addLog("│   ⚠ Download error: " + e.message)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            addLog("├── downloadReceiver: id=" + id + " ourId=" + downloadId)
            if (id == downloadId) {
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor = dm.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    addLog("│   Status: " + status + " (8=SUCCESS)")
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        val uri = dm.getUriForDownloadedFile(downloadId)
                        addLog("│   URI: " + uri)
                        addLog("│   Launching install intent")
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(installIntent)
                    }
                }
                cursor.close()
            }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for (l in r.split("\r", "\n", " ")) { val h = l.trim(); if (h.length == 4 && h.all { it in "0123456789ABCDEFabcdef" }) { when (h[0].uppercaseChar()) { '0' -> c.add("P0" + h.substring(1)); '1' -> c.add("P1" + h.substring(1)); '2' -> c.add("P2" + h.substring(1)); '3' -> c.add("P3" + h.substring(1)) } } }
        return c
    }

    override fun onDestroy() {
        addLog("┌── onDestroy")
        addLog("│   Unregistering download receiver")
        unregisterReceiver(downloadReceiver)
        addLog("│   Stopping data collection")
        stop()
        addLog("│   Disconnecting BT")
        bm.disconnect()
        addLog("└── onDestroy DONE")
        super.onDestroy()
    }
}
