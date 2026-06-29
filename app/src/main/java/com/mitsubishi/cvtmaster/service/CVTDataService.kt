package com.mitsubishi.cvtmaster.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mitsubishi.cvtmaster.CVTMasterApplication
import com.mitsubishi.cvtmaster.R
import com.mitsubishi.cvtmaster.elm327.BluetoothManager
import com.mitsubishi.cvtmaster.elm327.ConnectionState
import com.mitsubishi.cvtmaster.elm327.JatcoCVTData
import com.mitsubishi.cvtmaster.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CVTDataService : Service() {
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val _cvtData = MutableStateFlow<JatcoCVTData?>(null)
    val cvtData: StateFlow<JatcoCVTData?> = _cvtData
    
    private var collectionJob: Job? = null
    var bluetoothManager: BluetoothManager? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): CVTDataService = this@CVTDataService
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            CVTMasterApplication.CHANNEL_SERVICE.hashCode(),
            createNotification()
        )
        return START_STICKY
    }
    
    fun startDataCollection() {
        collectionJob?.cancel()
        collectionJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (bluetoothManager?.connectionState?.value == ConnectionState.READY) {
                        val response = bluetoothManager?.sendRawCommand("01 00")
                    }
                } catch (e: Exception) {
                    // Ignore
                }
                delay(500)
            }
        }
    }
    
    fun stopDataCollection() {
        collectionJob?.cancel()
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CVTMasterApplication.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_collecting))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
