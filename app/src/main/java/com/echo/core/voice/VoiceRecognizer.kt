package com.echo.core.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles voice recognition using Android SpeechRecognizer API
 * Supports offline recognition when language packs are available
 */
@Singleton
class VoiceRecognizer @Inject constructor(
    private val context: Context
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private val tag = "VoiceRecognizer"

    /**
     * Check if speech recognition is available on device
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * Start listening for voice input
     * Returns a Flow that emits recognition results
     */
    fun listen(): Flow<VoiceResult> = callbackFlow {
        if (!isAvailable()) {
            trySend(VoiceResult.Error("Speech recognition not available"))
            close()
            return@callbackFlow
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(tag, "Ready for speech")
                    trySend(VoiceResult.ReadyForSpeech)
                }

                override fun onBeginningOfSpeech() {
                    Log.d(tag, "Beginning of speech")
                    trySend(VoiceResult.SpeechStarted)
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Volume level changed
                    trySend(VoiceResult.VolumeChanged(rmsdB))
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Audio buffer received
                }

                override fun onEndOfSpeech() {
                    Log.d(tag, "End of speech")
                    trySend(VoiceResult.SpeechEnded)
                }

                override fun onError(error: Int) {
                    val errorMessage = getErrorMessage(error)
                    Log.e(tag, "Error: $errorMessage")
                    trySend(VoiceResult.Error(errorMessage))
                    close()
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        val confidence = confidences?.getOrNull(0) ?: 0f
                        Log.d(tag, "Result: $text (confidence: $confidence)")
                        trySend(VoiceResult.Success(text, confidence))
                    } else {
                        trySend(VoiceResult.Error("No results"))
                    }
                    close()
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        trySend(VoiceResult.PartialResult(matches[0]))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {
                    // Not used
                }
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Request offline recognition if available
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        speechRecognizer?.startListening(intent)

        awaitClose {
            stopListening()
        }
    }

    /**
     * Stop listening and cleanup
     */
    fun stopListening() {
        Log.d(tag, "Stopping speech recognizer")
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun getErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
            SpeechRecognizer.ERROR_NETWORK -> "Network error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
            else -> "Unknown error: $error"
        }
    }
}

/**
 * Voice recognition results
 */
sealed class VoiceResult {
    object ReadyForSpeech : VoiceResult()
    object SpeechStarted : VoiceResult()
    object SpeechEnded : VoiceResult()
    data class VolumeChanged(val rmsdB: Float) : VoiceResult()
    data class PartialResult(val text: String) : VoiceResult()
    data class Success(val text: String, val confidence: Float) : VoiceResult()
    data class Error(val message: String) : VoiceResult()
}
