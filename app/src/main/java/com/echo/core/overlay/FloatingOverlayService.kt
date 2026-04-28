package com.echo.core.overlay

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.echo.R
import com.echo.core.ai.SimpleGemmaEngine
import com.echo.core.voice.VoiceResult
import com.echo.core.voice.VoiceService
import com.echo.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Floating overlay service that shows a draggable bubble
 * User can tap the bubble to activate Echo
 */
@AndroidEntryPoint
class FloatingOverlayService : Service() {

    private val tag = "FloatingOverlayService"
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    
    private var voiceService: VoiceService? = null
    private var isVoiceServiceBound = false
    
    @Inject
    lateinit var gemmaEngine: SimpleGemmaEngine
    
    private var isListening = false
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Stop commands
    private val stopCommands = Constants.STOP_COMMANDS

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? VoiceService.VoiceServiceBinder
            voiceService = binder?.getService()
            isVoiceServiceBound = true
            Log.d(tag, "Voice service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isVoiceServiceBound = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Bind to voice service
        val intent = Intent(this, VoiceService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        createOverlay()
    }

    private fun createOverlay() {
        // Create circular overlay view
        val bubbleSize = 120 // Size in pixels
        val overlayView = View(this).apply {
            // Set circular background with microphone icon color
            background = resources.getDrawable(android.R.drawable.presence_audio_online, null)
            // Make it circular
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }

        // Layout params for the overlay
        val params = WindowManager.LayoutParams(
            120, // width - circular size
            120, // height - circular size
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        // Handle touch events
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        overlayView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(overlayView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Check if this was a tap (not a drag)
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy < 100) { // Single tap threshold
                        onBubbleTapped()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(overlayView, params)
        this.overlayView = overlayView
        
        Log.d(tag, "Overlay created")
    }

    private fun onBubbleTapped() {
        Log.d(tag, "Bubble tapped! Activating Echo...")
        
        if (voiceService == null) {
            Log.e(tag, "Voice service not available, rebinding...")
            val intent = Intent(this, VoiceService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            return
        }
        
        if (isListening) {
            stopListening()
            return
        }

        startListening()
    }

    private fun startListening() {
        if (isListening) return

        isListening = true
        Log.d(tag, "Starting to listen...")

        voiceService?.speak(Constants.ACTIVATION_MESSAGE) { }

        voiceService?.startListening { result ->
            handleVoiceResult(result)
        }

        // Auto-timeout
        serviceScope.launch {
            delay(Constants.VOICE_TIMEOUT_MS)
            if (isListening) {
                stopListening()
                voiceService?.speak(Constants.TIMEOUT_MESSAGE)
            }
        }
    }

    private fun stopListening() {
        if (!isListening) return
        
        isListening = false
        voiceService?.stopListening()
        Log.d(tag, "Stopped listening")
    }

    private fun handleVoiceResult(result: VoiceResult) {
        when (result) {
            is VoiceResult.Success -> {
                Log.d(tag, "Recognized: ${result.text}")
                processCommand(result.text)
            }
            is VoiceResult.Error -> {
                Log.e(tag, "Voice error: ${result.message}")
                if (result.message == "No speech input") {
                    voiceService?.speak(Constants.NO_SPEECH_MESSAGE)
                } else {
                    voiceService?.speak(Constants.ERROR_MESSAGE)
                }
                isListening = false
            }
            else -> {}
        }
    }

    private fun processCommand(command: String) {
        val lowerCommand = command.lowercase()

        if (stopCommands.any { lowerCommand.contains(it) }) {
            voiceService?.speak(Constants.GOODBYE_MESSAGE)
            stopListening()
            return
        }

        // Use Gemma AI for intelligent response
        serviceScope.launch {
            val response = if (gemmaEngine.isReady()) {
                Log.d(tag, "Using Gemma AI for response")
                gemmaEngine.chatStreaming(command) { /* Ignore streaming for voice */ }
            } else {
                Log.d(tag, "Gemma not ready, using fallback")
                "You said: $command"
            }
            
            voiceService?.speak(response)
            stopListening()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let { windowManager.removeView(it) }
        if (isVoiceServiceBound) {
            unbindService(serviceConnection)
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java)
            context.stopService(intent)
        }
    }
}
