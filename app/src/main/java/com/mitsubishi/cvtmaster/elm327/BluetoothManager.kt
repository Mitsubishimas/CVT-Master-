package com.mitsubishi.cvtmaster.elm327

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.*

data class BluetoothDevice(
    val name: String,
    val macAddress: String,
    val rssi: Int = 0
)

enum class BluetoothConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    INITIALIZING,
    READY,
    ERROR
}

class BluetoothManager(private val context: Context) {
    
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private val _connectionState = MutableStateFlow(BluetoothConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState
    
    private val _discoveredDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDevice>> = _discoveredDevices
    
    private val _rawData = MutableStateFlow("")
    val rawData: StateFlow<String> = _rawData
    
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    
    companion object {
        // Стандартный UUID для SPP (Serial Port Profile)
        val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        
        // AT команды для инициализации ELM327
        const val CMD_RESET = "ATZ\r"
        const val CMD_ECHO_OFF = "ATE0\r"
        const val CMD_HEADERS_ON = "ATH1\r"
        const val CMD_LINEFEED_OFF = "ATL0\r"
        const val CMD_SPACES_ON = "ATS1\r"
        const val CMD_PROTOCOL_AUTO = "ATSP0\r"
        const val CMD_SET_CAN_11BIT = "ATCP11\r"
        const val CMD_SET_CAN_29BIT = "ATCP29\r"
        const val CMD_CAN_AUTO_FORMAT = "ATCAF0\r"
    }
    
    /**
     * Проверка поддержки Bluetooth
     */
    fun isBluetoothSupported(): Boolean {
        return bluetoothAdapter != null
    }
    
    /**
     * Проверка включен ли Bluetooth
     */
    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }
    
    /**
     * Сканирование доступных устройств
     */
    @Suppress("DEPRECATION")
    fun startScanning() {
        if (!hasBluetoothPermission()) {
            _connectionState.value = BluetoothConnectionState.ERROR
            return
        }
        
        _connectionState.value = BluetoothConnectionState.SCANNING
        val discoveredDevices = mutableListOf<BluetoothDevice>()
        
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: android.bluetooth.BluetoothDevice? = 
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    BluetoothDevice.EXTRA_DEVICE, 
                                    android.bluetooth.BluetoothDevice::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                        
                        device?.let {
                            val name = it.name ?: "Неизвестное устройство"
                            // Фильтруем только OBD устройства
                            if (name.contains("OBD", true) || 
                                name.contains("ELM", true) ||
                                name.contains("Vgate", true) ||
                                name.contains("Viecar", true)) {
                                
                                discoveredDevices.add(
                                    BluetoothDevice(
                                        name = name,
                                        macAddress = it.address,
                                        rssi = rssi
                                    )
                                )
                                _discoveredDevices.value = discoveredDevices.toList()
                            }
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _connectionState.value = BluetoothConnectionState.DISCONNECTED
                        context?.unregisterReceiver(this)
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        
        context.registerReceiver(receiver, filter)
        bluetoothAdapter?.startDiscovery()
    }
    
    /**
     * Подключение к устройству
     */
    fun connectToDevice(macAddress: String) {
        scope.launch {
            try {
                _connectionState.value = BluetoothConnectionState.CONNECTING
                
                val device = bluetoothAdapter?.getRemoteDevice(macAddress)
                    ?: throw Exception("Устройство не найдено")
                
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                
                // Отключаем обнаружение для стабильного соединения
                bluetoothAdapter?.cancelDiscovery()
                
                withContext(Dispatchers.IO) {
                    bluetoothSocket?.connect()
                }
                
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                
                // Инициализируем ELM327
                initializeELM327()
                
            } catch (e: Exception) {
                e.printStackTrace()
                _connectionState.value = BluetoothConnectionState.ERROR
                disconnect()
            }
        }
    }
    
    /**
     * Инициализация ELM327 с автоопределением протокола Mitsubishi
     */
    private suspend fun initializeELM327() {
        _connectionState.value = BluetoothConnectionState.INITIALIZING
        
        val initCommands = listOf(
            CMD_RESET,
            CMD_ECHO_OFF,
            CMD_LINEFEED_OFF,
            CMD_SPACES_ON,
            CMD_HEADERS_ON,
            CMD_CAN_AUTO_FORMAT
        )
        
        for (command in initCommands) {
            val response = sendCommandAndWait(command)
            delay(100)
            
            // Проверяем, что получили OK
            if (!response.contains("OK") && command != CMD_RESET) {
                // Пробуем альтернативную инициализацию для клонов
                sendCommandAndWait("ATWS\r")  // Warm Start
                delay(500)
            }
        }
        
        // Автоопределение протокола Mitsubishi (обычно CAN 11bit 500kbps)
        sendCommandAndWait("ATSP6\r")  // ISO 15765-4 CAN (11bit, 500kbps)
        delay(200)
        
        // Устанавливаем фильтр на ID блоков Mitsubishi
        sendCommandAndWait("ATCF 7E0\r")  // ECM
        sendCommandAndWait("ATCF 7E2\r")  // TCM
        sendCommandAndWait("ATCF 7E8\r")  // ABS
        
        _connectionState.value = BluetoothConnectionState.READY
    }
    
    /**
     * Отправка команды и ожидание ответа
     */
    private suspend fun sendCommandAndWait(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write(command.toByteArray())
                outputStream?.flush()
                
                delay(100)  // Ждем ответ
                
                val buffer = ByteArray(1024)
                val bytesRead = inputStream?.read(buffer) ?: 0
                
                if (bytesRead > 0) {
                    val response = String(buffer, 0, bytesRead)
                    _rawData.value = response
                    response
                } else {
                    ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }
    
    /**
     * Запрос данных CVT
     */
    suspend fun requestCVTData(): JatcoCVTData? {
        if (_connectionState.value != BluetoothConnectionState.READY) return null
        
        val parser = JatcoPIDParser()
        val allData = StringBuilder()
        
        // Запрашиваем все PID последовательно
        val pids = listOf(
            JatcoPIDParser.PID_OIL_TEMP,
            JatcoPIDParser.PID_PRIMARY_PRESSURE,
            JatcoPIDParser.PID_SECONDARY_PRESSURE,
            JatcoPIDParser.PID_OIL_DEGRADATION,
            JatcoPIDParser.PID_ENGINE_RPM,
            JatcoPIDParser.PID_STEP_MOTOR,
            JatcoPIDParser.PID_TCC_STATUS,
            JatcoPIDParser.PID_GEAR_POSITION,
            JatcoPIDParser.PID_BELT_WEAR
        )
        
        for (pid in pids) {
            val command = parser.createPIDRequest(pid)
            val response = sendCommandAndWait(command)
            allData.append(response).append("\n")
            delay(50)  // Небольшая задержка между запросами
        }
        
        return parser.parseELM327Response(allData.toString())
    }
    
    /**
     * Сброс счетчика деградации масла
     */
    suspend fun resetOilDegradation(): Boolean {
        return try {
            _connectionState.value = BluetoothConnectionState.INITIALIZING
            
            // Специальная сервисная команда для Jatco CVT
            // Требует правильной последовательности
            
            // Шаг 1: Вход в сервисный режим
            sendCommandAndWait("AT SH 7E2\r")
            delay(100)
            
            // Шаг 2: Запрос сервисного доступа (Seed-Key для Mitsubishi)
            val seedResponse = sendCommandAndWait("22 10 00\r")
            delay(100)
            
            // Шаг 3: Отправка ключа (зависит от модели, здесь упрощенный вариант)
            sendCommandAndWait("2E 10 00 FF FF FF FF\r")
            delay(200)
            
            // Шаг 4: Команда сброса деградации
            val resetResponse = sendCommandAndWait("30 00 01\r")
            delay(200)
            
            _connectionState.value = BluetoothConnectionState.READY
            resetResponse.contains("OK") || resetResponse.contains("60")
            
        } catch (e: Exception) {
            e.printStackTrace()
            _connectionState.value = BluetoothConnectionState.ERROR
            false
        }
    }
    
    /**
     * Отключение
     */
    fun disconnect() {
        try {
            inputStream?.close()
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            inputStream = null
            outputStream = null
            bluetoothSocket = null
            _connectionState.value = BluetoothConnectionState.DISCONNECTED
        }
    }
    
    /**
     * Проверка разрешений Bluetooth
     */
    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
