package com.echo.core.ai

import android.content.Context
import android.util.Log
import com.echo.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemma AI engine using llama.cpp
 */
@Singleton
class SimpleGemmaEngine @Inject constructor(
    private val context: Context,
    private val llamaCppEngine: LlamaCppEngine
) {
    private val tag = "SimpleGemmaEngine"
    private var isInitialized = false
    
    /**
     * Initialize Gemma 4 2B
     */
    suspend fun initialize(onProgress: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(tag, "Already initialized")
            return@withContext true
        }
        
        try {
            onProgress("Loading Gemma 4 2B...")
            Log.d(tag, "Initializing Gemma 4 2B with llama.cpp")
            
            // Use SD card path - consistent with LlamaCppEngine default
            val modelPath = Constants.MODEL_PATH
            Log.d(tag, "Model path: $modelPath")
            
            isInitialized = llamaCppEngine.initialize(modelPath)
            
            if (isInitialized) {
                onProgress("Gemma 4 2B ready!")
                Log.d(tag, "✅ Gemma 4 2B initialized successfully!")
            } else {
                onProgress("Failed to load Gemma")
                Log.e(tag, "❌ Failed to initialize Gemma")
            }
            
            isInitialized
            
        } catch (e: Exception) {
            Log.e(tag, "Failed to initialize", e)
            onProgress("Error: ${e.message}")
            false
        }
    }
    
    /**
     * Generate an intelligent response
     */
    /**
     * Chat with streaming responses
     */
    suspend fun chatStreaming(
        userMessage: String,
        onToken: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        if (!isReady()) {
            Log.w(tag, "Gemma not ready, using fallback")
            return@withContext getFallbackResponse(userMessage)
        }
        
        try {
            val prompt = buildGemmaPrompt(userMessage)
            Log.d(tag, "Generating response for: $userMessage")
            
            val response = llamaCppEngine.generateStreaming(
                prompt = prompt, 
                maxTokens = Constants.MAX_TOKENS,
                onToken = onToken
            )
            
            if (response.isBlank() || response.startsWith("Error:")) {
                return@withContext getFallbackResponse(userMessage)
            }
            
            response.trim()
                .replace(Regex("\\s+"), " ")
                .substringBefore("<end_of_turn>")
                .trim()
            
        } catch (e: Exception) {
            Log.e(tag, "Error generating response", e)
            getFallbackResponse(userMessage)
        }
    }
    
    /**
     * Build Gemma-2 instruction prompt format
     */
    private fun buildGemmaPrompt(userMessage: String): String {
        return """<start_of_turn>user
$userMessage<end_of_turn>
<start_of_turn>model
"""
    }
    
    /**
     * Fallback response when Gemma is not available
     */
    private fun getFallbackResponse(userMessage: String): String {
        val lowerMessage = userMessage.lowercase()
        
        return when {
            lowerMessage.contains("hello") || lowerMessage.contains("hi") -> 
                "Hello! I'm Echo, your AI assistant. I'm currently running in basic mode. How can I help you today?"
            
            lowerMessage.contains("how are you") -> 
                "I'm functioning well in basic mode! Once we integrate Gemma 4 2B, I'll be even smarter. What would you like to know?"
            
            lowerMessage.contains("what") && lowerMessage.contains("name") -> 
                "I'm Echo, your personal AI assistant."
            
            lowerMessage.contains("thank") -> 
                "You're welcome! Happy to help."
            
            lowerMessage.contains("what can you do") || lowerMessage.contains("help") ->
                "Right now I'm in basic mode, but soon I'll be powered by Gemma 4 2B to help you with questions, conversations, and controlling your phone!"
            
            lowerMessage.contains("weather") ->
                "I don't have access to weather data yet, but that's a feature we can add!"
                
            lowerMessage.contains("time") || lowerMessage.contains("date") ->
                "Current time: ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
            
            userMessage.endsWith("?") ->
                "That's a great question! I'm currently in basic mode while we integrate Gemma 4 2B. Once that's done, I'll give you much better answers!"
            
            else -> 
                "I hear you! I'm Echo in basic mode right now. We're working on integrating Gemma 4 2B for smarter responses. Is there anything specific you'd like to know?"
        }
    }
    
    /**
     * Check if Gemma is ready
     */
    fun isReady(): Boolean {
        return llamaCppEngine.isReady()
    }
    
    /**
     * Cancel ongoing generation
     */
    fun cancelGeneration() {
        llamaCppEngine.cancelGeneration()
    }
    
    /**
     * Clean up resources
     */
    fun close() {
        llamaCppEngine.cleanup()
        isInitialized = false
        Log.d(tag, "Gemma closed")
    }
}
