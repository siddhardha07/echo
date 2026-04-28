package com.echo.util

/**
 * Application-wide constants
 */
object Constants {
    
    // Voice Recognition
    const val VOICE_TIMEOUT_MS = 10_000L
    const val SPEECH_RATE = 1.0f
    const val SPEECH_PITCH = 1.0f
    
    // Double-tap Detection
    const val TAP_TIMEOUT_MS = 500L // Increased for better detection
    const val DOUBLE_TAP_THRESHOLD = 2
    
    // Stop Commands
    val STOP_COMMANDS = setOf(
        "echo stop",
        "stop",
        "echo sleep",
        "sleep",
        "cancel",
        "nevermind",
        "never mind"
    )
    
    // Default Responses
    const val ACTIVATION_MESSAGE = "Yes?"
    const val TIMEOUT_MESSAGE = "Timeout. I'm going to sleep."
    const val GOODBYE_MESSAGE = "Going to sleep. Goodbye!"
    const val NO_SPEECH_MESSAGE = "I didn't hear anything."
    const val ERROR_MESSAGE = "Sorry, I had trouble hearing you."
    
    // Model Configuration
    const val MODEL_PATH = "/sdcard/models/gemma4-2b-proper.gguf"
    const val MAX_TOKENS = 512
}
