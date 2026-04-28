package com.echo.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Path
import android.os.IBinder
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.echo.core.voice.VoiceResult
import com.echo.core.voice.VoiceService
import com.echo.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Accessibility service that detects double-tap gestures to activate Echo
 */
class EchoAccessibilityService : AccessibilityService() {

    private val tag = "EchoAccessibilityService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    // Double-tap detection
    private var tapCount = 0
    private var lastTapTime = 0L
    private val tapTimeout = Constants.TAP_TIMEOUT_MS
    private val doubleTapThreshold = Constants.DOUBLE_TAP_THRESHOLD
    private var tapResetJob: Job? = null

    // Voice service connection
    private var voiceService: VoiceService? = null
    private var isVoiceServiceBound = false
    private var isListening = false

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
            Log.d(tag, "Voice service disconnected")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "✅ Accessibility service connected and ready")
        
        // Bind to voice service
        val intent = Intent(this, VoiceService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // Log all events for debugging
        Log.v(tag, "Event: ${event.eventType}, Package: ${event.packageName}")

        // Detect touch/tap events - using multiple event types for compatibility
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(tag, "👆 Click detected in ${event.packageName}")
                handleTap()
            }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                Log.d(tag, "👆 Touch end detected")
                handleTap()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Some devices trigger this on touch
                Log.v(tag, "Window state changed: ${event.packageName}")
            }
        }
    }

    /**
     * Handle tap event and detect double-tap pattern
     */
    private fun handleTap() {
        val currentTime = System.currentTimeMillis()
        
        // Reset tap count if too much time elapsed
        if (currentTime - lastTapTime > tapTimeout) {
            tapCount = 0
            Log.v(tag, "Reset tap count (timeout)")
        }

        tapCount++
        lastTapTime = currentTime

        Log.d(tag, "👆 Tap #$tapCount detected")

        // Cancel previous reset job
        tapResetJob?.cancel()

        // Reset tap count after timeout
        tapResetJob = serviceScope.launch {
            delay(tapTimeout)
            if (tapCount < doubleTapThreshold) {
                Log.v(tag, "Resetting tap count (only $tapCount taps)")
            }
            tapCount = 0
        }

        // Check for double-tap
        if (tapCount >= doubleTapThreshold) {
            tapCount = 0
            tapResetJob?.cancel()
            onDoubleTapDetected()
        }
    }

    /**
     * Called when double-tap is detected
     */
    private fun onDoubleTapDetected() {
        Log.d(tag, "🎯 DOUBLE-TAP DETECTED! Activating Echo...")
        
        if (voiceService == null) {
            Log.e(tag, "❌ Voice service not available")
            // Try to rebind
            val intent = Intent(this, VoiceService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            return
        }

        // If already listening, stop
        if (isListening) {
            Log.d(tag, "Already listening, stopping...")
            stopListening()
            return
        }

        // Start listening
        startListening()
    }

    /**
     * Start listening for voice commands
     */
    private fun startListening() {
        if (isListening) return
        
        val service = voiceService
        if (service == null) {
            Log.e(tag, "Cannot start listening - voice service is null")
            return
        }

        isListening = true
        Log.d(tag, "Starting to listen...")

        // Speak activation message
        service.speak(Constants.ACTIVATION_MESSAGE) { /* Ignore result */ }

        // Start voice recognition
        service.startListening { result ->
            handleVoiceResult(result)
        }

        // Auto-timeout after 10 seconds
        serviceScope.launch {
            delay(Constants.VOICE_TIMEOUT_MS)
            if (isListening) {
                Log.d(tag, "Auto-timeout - stopping listening")
                stopListening()
                voiceService?.speak(Constants.TIMEOUT_MESSAGE)
            }
        }
    }

    /**
     * Stop listening for voice commands
     */
    private fun stopListening() {
        if (!isListening) return

        isListening = false
        voiceService?.stopListening()
        Log.d(tag, "Stopped listening")
    }

    /**
     * Handle voice recognition results
     */
    private fun handleVoiceResult(result: VoiceResult) {
        when (result) {
            is VoiceResult.ReadyForSpeech -> {
                Log.d(tag, "Ready for speech")
            }
            
            is VoiceResult.SpeechStarted -> {
                Log.d(tag, "User started speaking")
            }
            
            is VoiceResult.PartialResult -> {
                Log.d(tag, "Partial: ${result.text}")
            }
            
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

    /**
     * Process voice command
     * In Phase 1, just echo back what user said
     */
    private fun processCommand(command: String) {
        val lowerCommand = command.lowercase()

        // Check for stop commands
        if (stopCommands.any { lowerCommand.contains(it) }) {
            voiceService?.speak(Constants.GOODBYE_MESSAGE)
            stopListening()
            return
        }

        // Phase 1: Simple echo
        // Later this will be replaced with Gemma AI processing
        Log.d(tag, "Processing command: $command")
        
        val response = "You said: $command"
        voiceService?.speak(response)
        
        // Stop listening after responding
        stopListening()
    }

    override fun onInterrupt() {
        Log.d(tag, "Service interrupted")
        stopListening()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "Service destroyed")
        
        if (isVoiceServiceBound) {
            unbindService(serviceConnection)
            isVoiceServiceBound = false
        }
    }
}
