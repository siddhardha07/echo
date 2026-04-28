package com.echo.core.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles text-to-speech using Android TTS API
 */
@Singleton
class VoiceSynthesizer @Inject constructor(
    private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val tag = "VoiceSynthesizer"

    /**
     * Initialize TTS engine
     */
    fun initialize(): Flow<TtsResult> = callbackFlow {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                when (result) {
                    TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                        Log.e(tag, "Language not supported")
                        trySend(TtsResult.Error("Language not supported"))
                    }
                    else -> {
                        isInitialized = true
                        // Set speech rate and pitch
                        tts?.setSpeechRate(1.0f)
                        tts?.setPitch(1.0f)
                        Log.d(tag, "TTS initialized successfully")
                        trySend(TtsResult.Initialized)
                    }
                }
            } else {
                Log.e(tag, "TTS initialization failed")
                trySend(TtsResult.Error("TTS initialization failed"))
            }
            close()
        }

        awaitClose {
            // Cleanup handled separately
        }
    }

    /**
     * Speak text
     */
    fun speak(text: String): Flow<TtsResult> = callbackFlow {
        if (!isInitialized || tts == null) {
            trySend(TtsResult.Error("TTS not initialized"))
            close()
            return@callbackFlow
        }

        val utteranceId = System.currentTimeMillis().toString()

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                Log.d(tag, "Speech started: $text")
                trySend(TtsResult.SpeechStarted)
            }

            override fun onDone(utteranceId: String?) {
                Log.d(tag, "Speech completed: $text")
                trySend(TtsResult.SpeechCompleted)
                close()
            }

            override fun onError(utteranceId: String?) {
                Log.e(tag, "Speech error")
                trySend(TtsResult.Error("Speech error"))
                close()
            }
        })

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            trySend(TtsResult.Error("Failed to start speaking"))
            close()
        }

        awaitClose {
            // Flow closed
        }
    }

    /**
     * Stop speaking
     */
    fun stop() {
        if (tts?.isSpeaking == true) {
            Log.d(tag, "Stopping speech")
            tts?.stop()
        }
    }

    /**
     * Check if currently speaking
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking ?: false
    }

    /**
     * Shutdown TTS engine
     */
    fun shutdown() {
        Log.d(tag, "Shutting down TTS")
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}

/**
 * TTS results
 */
sealed class TtsResult {
    object Initialized : TtsResult()
    object SpeechStarted : TtsResult()
    object SpeechCompleted : TtsResult()
    data class Error(val message: String) : TtsResult()
}
