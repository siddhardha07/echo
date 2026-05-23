package com.echo.core.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.echo.MainActivity
import com.echo.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service for voice recognition
 * Runs when Echo is actively listening
 */
@AndroidEntryPoint
class VoiceService : Service() {

    @Inject
    lateinit var voiceRecognizer: VoiceRecognizer

    @Inject
    lateinit var voiceSynthesizer: VoiceSynthesizer

    private val binder = VoiceServiceBinder()
    private val tag = "VoiceService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var isListening = false
    private var currentListeningJob: Job? = null

    inner class VoiceServiceBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service created")
        createNotificationChannel()
        
        // Initialize TTS
        serviceScope.launch {
            voiceSynthesizer.initialize().collect { result ->
                Log.d(tag, "TTS init result: $result")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "Service started")
        
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    /**
     * Start listening for voice input
     */
    fun startListening(callback: (VoiceResult) -> Unit) {
        if (isListening) {
            Log.w(tag, "Already listening")
            return
        }

        isListening = true
        currentListeningJob = serviceScope.launch {
            voiceRecognizer.listen().collect { result ->
                callback(result)
                
                // Stop listening after getting result or error
                when (result) {
                    is VoiceResult.Success, is VoiceResult.Error -> {
                        stopListening()
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        if (!isListening) return
        
        isListening = false
        currentListeningJob?.cancel()
        voiceRecognizer.stopListening()
        Log.d(tag, "Stopped listening")
    }

    /**
     * Speak text
     */
    fun speak(text: String, callback: (TtsResult) -> Unit = {}) {
        serviceScope.launch {
            voiceSynthesizer.speak(text).collect { result ->
                callback(result)
            }
        }
    }

    /**
     * Stop speaking
     */
    fun stopSpeaking() {
        voiceSynthesizer.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "Service destroyed")
        stopListening()
        voiceSynthesizer.shutdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "echo_voice_service"
        private const val NOTIFICATION_ID = 1001
    }
}
