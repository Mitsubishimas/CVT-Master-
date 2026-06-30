package com.mitsubishi.cvtmaster.ui

import android.Manifest
import android.app.DownloadManager
import android.bluetooth.BluetoothAdapter
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
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
        private const val VERSION = "v1.0.37"
        private const val PREFS = "cvt_prefs"
        private const val KEY_CHECK = "last_check"
        private const val KEY_LANG = "lang"
    }

    private lateinit var bm: BluetoothManager; private lateinit var logger: DataLogger
    private lateinit var tvStatus: TextView; private lateinit var tvTemp: TextView; private lateinit var tvTempSt: TextView
    private lateinit var tvDeg: TextView; private lateinit var tvPr1: TextView; private lateinit var tvPr2: TextView
    private lateinit var tvRpm: TextView; private lateinit var tvRatio: TextView; private lateinit var tvBelt: TextView
    private lateinit var tvTcc: TextView; private lateinit var tvGear: TextView; private lateinit var tvInfo: TextView
    private lateinit var tvLog: TextView; private lateinit var btnConn: Button
    private lateinit var cont: LinearLayout; private lateinit var graph: CVTGraphView
    private var job: Job? = null
    private val logLines = mutableListOf<String>()
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var logFile: File? = null
    private var dlId: Long = -1
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private var cvtModel = "Unknown"; private var deterioration = 0
    private var isRu = false

    private fun log(msg: String) {
        val line = sdf.format(Date()) + " " + msg
        logLines.add(line)
        try { if (logFile != null) FileWriter(logFile, true).use { it.write(line + "\n") } } catch (_: Exception) {}
        runOnUiThread { try { tvLog.text = logLines.takeLast(200).joinToString("\n") } catch (_: Exception) {} }
    }

    private fun t(key: String): String {
        val en = mapOf("connect" to "Connect","disconnect" to "Disconnect","off" to "Off","on" to "On","err" to "Error","init" to "Init...","ok" to "OK","no_bt" to "No Bluetooth","bt_off" to "Turn on Bluetooth","no_perm" to "No permission","no_dev" to "No paired devices","sel" to "Select ELM327","cancel" to "Cancel","read_dtc" to "Read DTC","clear_dtc" to "Clear DTC","no_err" to "No errors","cleared" to "Cleared","settings" to "Settings","cvt" to "CVT:","det" to "Deterioration:","lang" to "Language","detect" to "Detect CVT","read_det" to "Read Deterioration","reset_det" to "Reset Deterioration","reset_oil" to "Reset oil degradation","update" to "Check updates","install" to "Allow install","copy" to "Copy log","copied" to "Copied","reconnect" to "Reconnect ECU","conn_first" to "Connect first","graphs" to "Graphs","dtc" to "DTC","latest" to "Latest","checking" to "Checking...","download_q" to "Download?","yes" to "Yes","no" to "No","upd_title" to "Update","downloading" to "Downloading...","dl_started" to "Download started...","allowed" to "Already allowed","inst_title" to "Install","inst_msg" to "Allow unknown sources?","dash" to "Dashboard")
        val ru = mapOf("connect" to "Подключить","disconnect" to "Отключить","off" to "Выкл","on" to "Вкл","err" to "Ошибка","init" to "Инит...","ok" to "ОК","no_bt" to "Нет Bluetooth","bt_off" to "Включите Bluetooth","no_perm" to "Нет разрешения","no_dev" to "Нет устройств","sel" to "Выбор ELM327","cancel" to "Отмена","read_dtc" to "Чтение DTC","clear_dtc" to "Сброс DTC","no_err" to "Ошибок нет","cleared" to "Сброшено","settings" to "Настройки","cvt" to "CVT:","det" to "Износ:","lang" to "Язык","detect" to "Определить CVT","read_det" to "Читать износ","reset_det" to "Сбросить износ","reset_oil" to "Сброс деградации","update" to "Обновления","install" to "Установка","copy" to "Копировать","copied" to "Скопировано","reconnect" to "Переподключить","conn_first" to "Подключитесь","graphs" to "Графики","dtc" to "Ошибки","latest" to "Актуально","checking" to "Проверка...","download_q" to "Скачать?","yes" to "Да","no" to "Нет","upd_title" to "Обновление","downloading" to "Загрузка...","dl_started" to "Загрузка началась...","allowed" to "Уже разрешено","inst_title" to "Установка","inst_msg" to "Разрешить уст. из неизв. источников?","dash" to "Приборы")
        return if (isRu) ru[key] ?: key else en[key] ?: key
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        isRu = prefs.getString(KEY_LANG, "system") == "ru"
        val dir = File(filesDir, "logs"); dir.mkdirs()
        logFile = File(dir, "cvt_log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".txt")
        log("=== CVT Master $VERSION ===")
        setContentView(R.layout.activity_main)
        tvStatus = findViewById(R.id.tv_connection_status)
        btnConn = findViewById(R.id.btn_connect)
        cont = findViewById(R.id.content_container)
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
        setupNav()
        reqPerm()
        observe()
        registerReceiver(dlReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - prefs.getLong(KEY_CHECK, 0) > 604800000) checkUpdate(false)
    }

    private fun setupNav() {
        val rail = findViewById<com.google.android.material.navigationrail.NavigationRailView>(R.id.nav_rail)
        val bn = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)
        if (rail != null) {
            rail.menu.findItem(R.id.nav_dashboard).title = t("dash")
            rail.menu.findItem(R.id.nav_graphs).title = t("graphs")
            rail.menu.findItem(R.id.nav_dtc).title = t("dtc")
            rail.menu.findItem(R.id.nav_settings).title = t("settings")
            rail.setOnItemSelectedListener { handleNav(it.itemId); true }
        } else if (bn != null) {
            bn.menu.findItem(R.id.nav_dashboard).title = t("dash")
            bn.menu.findItem(R.id.nav_graphs).title = t("graphs")
            bn.menu.findItem(R.id.nav_dtc).title = t("dtc")
            bn.menu.findItem(R.id.nav_settings).title = t("settings")
            bn.setOnItemSelectedListener { handleNav(it.itemId); true }
        }
    }

    private fun handleNav(id: Int) {
        cont.removeAllViews()
        when (id) {
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
                l.addView(Button(this).apply { text = t("read_dtc"); setOnClickListener { readDTC() } })
                l.addView(Button(this).apply { text = t("clear_dtc"); setOnClickListener { clearDTC() } })
                cont.addView(l)
            }
            R.id.nav_settings -> {
                val scroll = ScrollView(this)
                val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
                l.addView(TextView(this).apply { text = t("cvt") + " " + cvtModel; setTextColor(getColor(R.color.white)); textSize = 16f })
                l.addView(TextView(this).apply { text = t("det") + " " + deterioration; setTextColor(if(deterioration>210000)Color.RED else Color.GREEN); textSize = 14f; setPadding(0, 0, 0, 16) })
                l.addView(Button(this).apply { text = t("lang"); setOnClickListener { showLangDialog() } })
                l.addView(Button(this).apply { text = t("detect"); setOnClickListener { lifecycleScope.launch { detectCVT() } } })
                l.addView(Button(this).apply { text = t("read_det"); setOnClickListener { lifecycleScope.launch { readDeterioration() } } })
                l.addView(Button(this).apply { text = t("reset_det"); setOnClickListener { lifecycleScope.launch { resetDeterioration() } } })
                l.addView(Button(this).apply { text = t("reset_oil"); setOnClickListener { lifecycleScope.launch { try { val r = OilDegradationReset().resetJF011E(bm); runOnUiThread { Toast.makeText(this@MainActivity, r.message, Toast.LENGTH_SHORT).show() } } catch (_: Exception) {} } } })
                l.addView(Button(this).apply { text = t("update"); setOnClickListener { checkUpdate(true) } })
                l.addView(Button(this).apply { text = t("install"); setOnClickListener { instPerm() } })
                l.addView(Button(this).apply { text = t("copy"); setOnClickListener { val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("CVT Log", logLines.joinToString("\n"))); Toast.makeText(this@MainActivity, t("copied"), Toast.LENGTH_SHORT).show() } })
                l.addView(Button(this).apply { text = t("reconnect"); setOnClickListener { if(bm.connectionState.value==ConnectionState.READY)initELM() else Toast.makeText(this@MainActivity,t("conn_first"),Toast.LENGTH_SHORT).show() } })
                l.addView(TextView(this).apply { text = "LOG:"; setTextColor(0xFF888888.toInt()); textSize = 12f; setPadding(0, 16, 0, 4) })
                l.addView(tvLog)
                scroll.addView(l); cont.addView(scroll)
            }
        }
    }

    private fun showLangDialog() {
        val items = arrayOf("System", "English", "Русский"); val vals = arrayOf("system", "en", "ru")
        val cur = prefs.getString(KEY_LANG, "system") ?: "system"
        AlertDialog.Builder(this).setTitle(t("lang")).setSingleChoiceItems(items, vals.indexOf(cur)) { d, w ->
            prefs.edit().putString(KEY_LANG, vals[w]).apply()
            val loc = when(vals[w]){"ru"->Locale("ru");"en"->Locale("en");else->return@setSingleChoiceItems}
            Locale.setDefault(loc); resources.updateConfiguration(Configuration(resources.configuration).apply{setLocale(loc)},resources.displayMetrics)
            isRu = vals[w]=="ru"; d.dismiss(); recreate()
        }.setNegativeButton(t("cancel"),null).show()
    }

    private fun reqPerm() {
        val p = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.BLUETOOTH)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (p.isNotEmpty()) ActivityCompat.requestPermissions(this, p.toTypedArray(), PERM)
    }

    override fun onRequestPermissionsResult(c: Int, perms: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(c, perms, g)
        if (c == PERM && g.all{it==PackageManager.PERMISSION_GRANTED}) {
            val bt = BluetoothAdapter.getDefaultAdapter()
            if (bt != null && !bt.isEnabled) startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT)
        }
    }

    private fun instPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this).setTitle(t("inst_title")).setMessage(t("inst_msg")).setPositiveButton(t("yes")){_,_->startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply{data=Uri.parse("package:$packageName")},REQ_INSTALL)}.setNegativeButton(t("no"),null).show()
        } else Toast.makeText(this, t("allowed"), Toast.LENGTH_SHORT).show()
    }

    override fun onActivityResult(c: Int, r: Int, d: Intent?) { super.onActivityResult(c, r, d) }

    private fun observe() {
        lifecycleScope.launch {
            bm.connectionState.collect { s -> runOnUiThread {
                when (s) {
                    ConnectionState.DISCONNECTED -> { tvStatus.text=t("off"); tvStatus.setTextColor(getColor(R.color.error)); btnConn.text=t("connect"); stop() }
                    ConnectionState.READY -> { tvStatus.text=t("on"); tvStatus.setTextColor(getColor(R.color.success)); btnConn.text=t("disconnect"); initELM() }
                    ConnectionState.CONNECTING -> { tvStatus.text="..."; btnConn.text="..." }
                    ConnectionState.ERROR -> { tvStatus.text=t("err"); tvStatus.setTextColor(getColor(R.color.error)); btnConn.text=t("connect") }
                    else -> {}
                }
            }}
        }
    }

    private fun initELM() = lifecycleScope.launch {
        try {
            runOnUiThread { tvStatus.text = t("init") }
            bm.sendRawCommand("ATZ"); delay(2000)
            bm.sendRawCommand("ATE0"); delay(200)
            bm.sendRawCommand("ATAL"); delay(100)
            bm.sendRawCommand("ATST32"); delay(100)
            bm.sendRawCommand("ATSW00"); delay(100)
            bm.sendRawCommand("ATSP6"); delay(500)
            bm.sendRawCommand("ATSH7E1"); delay(100)
            runOnUiThread { tvStatus.text = t("ok") }
            detectCVT()
            startLoop()
        } catch (_: Exception) {}
    }

    private fun connect() {
        val bt = BluetoothAdapter.getDefaultAdapter()
        if (bt == null) { Toast.makeText(this, t("no_bt"), Toast.LENGTH_LONG).show(); return }
        if (!bt.isEnabled) { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BT); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) { reqPerm(); return }
        val d = bm.getPairedDevices()
        if (d.isEmpty()) { Toast.makeText(this, t("no_dev"), Toast.LENGTH_LONG).show(); return }
        if (d.size == 1) bm.connectToDevice(d[0].address)
        else AlertDialog.Builder(this).setTitle(t("sel")).setItems(d.map{it.name}.toTypedArray()){_,w->bm.connectToDevice(d[w].address)}.setNegativeButton(t("cancel"),null).show()
    }

    private suspend fun detectCVT() {
        bm.sendRawCommand("2101"); delay(300)
        bm.sendRawCommand("1092"); delay(100)
        val resp = bm.sendRawCommand("2111"); delay(300)
        if (resp.contains("6111")) {
            cvtModel = when { resp.contains("0250") -> "Outlander 2013"; resp.contains("0260") -> "Compass 2007"; else -> "Mitsubishi CVT" }
        } else {
            val r2 = bm.sendRawCommand("2110"); delay(300)
            if (r2.contains("6110")) cvtModel = "Mitsubishi CVT (generic)"
        }
        runOnUiThread { tvInfo.text = "CVT: $cvtModel" }
        readDeterioration()
    }

    private suspend fun readDeterioration() {
        val resp = bm.sendRawCommand("2110"); delay(300)
        val clean = resp.replace(Regex("[^0-9A-F:]"), "")
        if (clean.length >= 83) {
            try {
                deterioration = clean.substring(54, 61).toInt(16)
                runOnUiThread { tvDeg.text = "$deterioration"; tvDeg.setTextColor(if(deterioration>210000)Color.RED else Color.GREEN) }
            } catch (_: Exception) {}
        }
    }

    private suspend fun resetDeterioration() {
        val seedResp = bm.sendRawCommand("2701"); delay(300)
        if (seedResp.contains("6701")) {
            val seed = seedResp.substringAfter("6701").take(8).trim()
            bm.sendRawCommand("2702${seed.reversed()}"); delay(300)
            if (bm.sendRawCommand("3103").contains("7103")) {
                runOnUiThread { Toast.makeText(this@MainActivity, "OK!", Toast.LENGTH_SHORT).show() }
                readDeterioration()
            }
        }
    }

    private fun startLoop() {
        job?.cancel()
        job = lifecycleScope.launch {
            while (isActive) {
                try {
                    val t = parseTemp(bm.sendRawCommand("0105")); delay(40)
                    val r = parseRPM(bm.sendRawCommand("010C")); delay(40)
                    val s = parseSpeed(bm.sendRawCommand("010D")); delay(40)
                    val m = parseMAF(bm.sendRawCommand("0110")); delay(40)
                    val f = parseFuel(bm.sendRawCommand("012F"))
                    if (t > 0 || r > 0) {
                        logger.addEntry(JatcoCVTData(oilTemperature = t, engineRPM = r, vehicleSpeed = s))
                        runOnUiThread {
                            tvTemp.text = String.format(Locale.US, "%.1f\u00b0C", t)
                            tvRpm.text = "$r rpm"; tvRatio.text = String.format(Locale.US, "%.0f km/h", s)
                            tvPr1.text = String.format(Locale.US, "%.1f g/s", m)
                            tvPr2.text = String.format(Locale.US, "%.0f%%", f)
                            graph.addTemperatureData(t)
                        }
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                delay(1000)
            }
        }
    }

    private fun stop() { job?.cancel() }

    private fun parseTemp(r: String): Float { for(l in r.split("\r","\n",">")){val p=l.trim().split(" ");if(p.size>=5&&p[2]=="41"&&p[3]=="05")return(p[4].toIntOrNull(16)?:0)-40f};return 0f}
    private fun parseRPM(r: String): Int { for(l in r.split("\r","\n",">")){val p=l.trim().split(" ");if(p.size>=6&&p[2]=="41"&&p[3]=="0C")return(((p[4].toIntOrNull(16)?:0)*256)+(p[5].toIntOrNull(16)?:0))/4};return 0}
    private fun parseSpeed(r: String): Float { for(l in r.split("\r","\n",">")){val p=l.trim().split(" ");if(p.size>=5&&p[2]=="41"&&p[3]=="0D")return(p[4].toIntOrNull(16)?:0).toFloat()};return 0f}
    private fun parseMAF(r: String): Float { for(l in r.split("\r","\n",">")){val p=l.trim().split(" ");if(p.size>=6&&p[2]=="41"&&p[3]=="10")return(((p[4].toIntOrNull(16)?:0)*256)+(p[5].toIntOrNull(16)?:0))/100f};return 0f}
    private fun parseFuel(r: String): Float { for(l in r.split("\r","\n",">")){val p=l.trim().split(" ");if(p.size>=5&&p[2]=="41"&&p[3]=="2F")return((p[4].toIntOrNull(16)?:0)*100f/255f)};return 0f}

    private fun readDTC() = lifecycleScope.launch {
        try {
            val r = bm.sendRawCommand("1800FF00")
            runOnUiThread {
                val c = parseDTC(r)
                if (c.isNotEmpty()) {
                    val info = c.mapNotNull{MitsubishiDTC.getDTCInfo(it)}
                    if (info.isNotEmpty()) AlertDialog.Builder(this@MainActivity).setTitle("DTC").setMessage(info.joinToString("\n\n"){it.code+": "+it.description}).setPositiveButton("OK",null).show()
                } else Toast.makeText(this@MainActivity, t("no_err"), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {}
    }

    private fun clearDTC() = lifecycleScope.launch {
        try { bm.sendRawCommand("14FF00"); runOnUiThread{Toast.makeText(this@MainActivity,t("cleared"),Toast.LENGTH_SHORT).show()} } catch (_: Exception) {}
    }

    private fun checkUpdate(force: Boolean) = lifecycleScope.launch {
        try {
            if (force) runOnUiThread { Toast.makeText(this@MainActivity, t("checking"), Toast.LENGTH_SHORT).show() }
            val json = withContext(Dispatchers.IO) {
                val c = URL(API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept","application/vnd.github.v3+json"); c.setRequestProperty("User-Agent","CVT")
                c.connectTimeout=10000;c.readTimeout=10000
                if(c.responseCode==200)c.inputStream.bufferedReader().readText() else throw Exception("HTTP ${c.responseCode}")
            }
            prefs.edit().putLong(KEY_CHECK, System.currentTimeMillis()).apply()
            val tag = json.split("\"tag_name\":\"")[1].split("\"")[0]
            val url = json.split("\"browser_download_url\":\"")[1].split("\"")[0]
            if (tag != VERSION) runOnUiThread { showUpdate(tag, url) }
            else if (force) runOnUiThread { Toast.makeText(this@MainActivity, t("latest"), Toast.LENGTH_SHORT).show() }
        } catch (_: Exception) {}
    }

    private fun showUpdate(tag: String, url: String) {
        AlertDialog.Builder(this).setTitle(t("upd_title")+" $tag").setMessage(t("download_q")).setPositiveButton(t("yes")){_,_->download(url)}.setNegativeButton(t("no"),null).show()
    }

    private fun download(url: String) {
        try {
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dlId = dm.enqueue(DownloadManager.Request(Uri.parse(url)).apply {
                setTitle("CVT Master Update"); setDescription(t("downloading"))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "CVT-Master-Update.apk")
            })
            Toast.makeText(this, t("dl_started"), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }

    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == dlId) {
                val dm = ctx?.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val uri = dm.getUriForDownloadedFile(dlId)
                if (uri != null) startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,"application/vnd.android.package-archive");addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)})
            }
        }
    }

    private fun parseDTC(r: String): List<String> {
        val c = mutableListOf<String>()
        for(l in r.split("\r","\n"," ")){val h=l.trim();if(h.length==4&&h.all{it in "0123456789ABCDEFabcdef"}){when(h[0].uppercaseChar()){'0'->c.add("P0"+h.substring(1));'1'->c.add("P1"+h.substring(1));'2'->c.add("P2"+h.substring(1));'3'->c.add("P3"+h.substring(1))}}}
        return c
    }

    override fun onDestroy() {
        try { unregisterReceiver(dlReceiver) } catch (_: Exception) {}
        stop(); bm.disconnect(); super.onDestroy()
    }
}
