package com.echo.core.ai

import android.content.Context
import android.util.Log
import com.echo.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native llama.cpp engine for Gemma 4 2B
 */
@Singleton
class LlamaCppEngine @Inject constructor(
    private val context: Context
) {
    private val tag = "LlamaCppEngine"
    private var isInitialized = false
    
    companion object {
        init {
            try {
                System.loadLibrary("echo-llama")
                Log.d("LlamaCppEngine", "Native library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("LlamaCppEngine", "Failed to load native library", e)
            }
        }
    }
    
    // Native methods
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int): String
    private external fun nativeCleanup()
    private external fun nativeIsLoaded(): Boolean
    
    /**
     * Initialize with Gemma 4 2B model
     */
    suspend fun initialize(modelPath: String = Constants.MODEL_PATH): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(tag, "Already initialized")
            return@withContext true
        }
        
        try {
            Log.d(tag, "Initializing llama.cpp with model: $modelPath")
            
            // Check if model file exists
            val file = java.io.File(modelPath)
            if (!file.exists()) {
                Log.e(tag, "Model file not found: $modelPath")
                return@withContext false
            }
            
            Log.d(tag, "Model file found: ${file.length() / (1024 * 1024)}MB")
            
            // Initialize native library
            isInitialized = nativeInit(modelPath)
            
            if (isInitialized) {
                Log.d(tag, "✅ Gemma 4 2B loaded successfully!")
            } else {
                Log.e(tag, "❌ Failed to load model")
            }
            
            isInitialized
            
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize", e)
            false
        }
    }
    
    /**
     * Generate response with streaming callback
     */
    suspend fun generateStreaming(
        prompt: String, 
        maxTokens: Int = 512,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            Log.e(tag, "Engine not initialized")
            return@withContext "Model not initialized"
        }
        
        try {
            Log.d(tag, "Generating response...")
            // Generate with token callback
            val result = nativeGenerateStreaming(prompt, maxTokens, onToken)
            Log.d(tag, "Generated ${result.length} characters")
            result
        } catch (e: Exception) {
            Log.e(tag, "Generation failed", e)
            "Error: ${e.message}"
        }
    }
    
    private external fun nativeGenerateStreaming(
        prompt: String,
        maxTokens: Int,
        onToken: (String) -> Unit
    ): String
    
    private external fun nativeCancelGeneration()
    
    /**
     * Cancel ongoing generation
     */
    fun cancelGeneration() {
        try {
            nativeCancelGeneration()
            Log.d(tag, "Generation cancelled")
        } catch (e: Exception) {
            Log.e(tag, "Failed to cancel generation", e)
        }
    }
    
    /**
     * Check if ready
     */
    fun isReady(): Boolean {
        return isInitialized && nativeIsLoaded()
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        if (isInitialized) {
            nativeCleanup()
            isInitialized = false
            Log.d(tag, "Cleaned up")
        }
    }
}
