package com.mitsubishi.cvtmaster.elm327

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class ELM327Device(
    val name: String,
    val address: String
)

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    INITIALIZING,
    READY,
    ERROR
}

class BluetoothManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothManager"
        private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
    
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState
    
    private val _discoveredDevices = MutableStateFlow<List<ELM327Device>>(emptyList())
    val discoveredDevices: StateFlow<List<ELM327Device>> = _discoveredDevices
    
    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse
    
    private var scanReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<ELM327Device> {
        if (!hasPermission()) return emptyList()
        
        return bluetoothAdapter?.bondedDevices?.map { device ->
            ELM327Device(
                name = device.name ?: "Unknown",
                address = device.address
            )
        } ?: emptyList()
    }
    
    @SuppressLint("MissingPermission")
    fun startScanning() {
        if (!hasPermission()) {
            _connectionState.value = ConnectionState.ERROR
            return
        }
        
        _connectionState.value = ConnectionState.SCANNING
        
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let {
                            val device = ELM327Device(it.name ?: "Unknown", it.address)
                            _discoveredDevices.value = _discoveredDevices.value + device
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        
        context.registerReceiver(scanReceiver, filter)
        bluetoothAdapter?.startDiscovery()
    }
    
    @SuppressLint("MissingPermission")
    fun connectToDevice(address: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.CONNECTING
                
                val device = bluetoothAdapter?.getRemoteDevice(address)
                    ?: throw Exception("Device not found")
                
                withContext(Dispatchers.IO) {
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    bluetoothAdapter?.cancelDiscovery()
                    bluetoothSocket?.connect()
                }
                
                inputStream = bluetoothSocket?.inputStream
                outputStream = bluetoothSocket?.outputStream
                
                _connectionState.value = ConnectionState.READY
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed", e)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }
    
    suspend fun sendRawCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            try {
                outputStream?.write("$command\r".toByteArray())
                outputStream?.flush()
                
                delay(100)
                
                val buffer = ByteArray(4096)
                val bytesRead = inputStream?.read(buffer) ?: 0
                val response = if (bytesRead > 0) String(buffer, 0, bytesRead) else ""
                _lastResponse.value = response
                response
            } catch (e: Exception) {
                Log.e(TAG, "Command failed: $command", e)
                ""
            }
        }
    }
    
    suspend fun resetOilDegradation(): Boolean {
        return try {
            sendRawCommand("AT SH 7E2")
            delay(100)
            sendRawCommand("22 10 00")
            delay(100)
            sendRawCommand("2E 10 00 FF FF FF FF")
            delay(200)
            val response = sendRawCommand("30 00 01")
            response.contains("60") || response.contains("OK")
        } catch (e: Exception) {
            false
        } finally {
            sendRawCommand("AT SH 7DF")
        }
    }
    
    fun disconnect() {
        scope.launch {
            try {
                inputStream?.close()
                outputStream?.close()
                bluetoothSocket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Disconnect error", e)
            } finally {
                inputStream = null
                outputStream = null
                bluetoothSocket = null
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }
    
    fun cleanup() {
        scope.cancel()
        disconnect()
    }
    
    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}
