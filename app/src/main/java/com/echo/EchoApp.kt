package com.echo

import android.app.Application
import android.util.Log
import com.echo.core.ai.SimpleGemmaEngine
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Echo Application class
 * Initializes Hilt dependency injection and Gemma AI
 */
@HiltAndroidApp
class EchoApp : Application() {

    @Inject
    lateinit var gemmaEngine: SimpleGemmaEngine
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Log.d("EchoApp", "Echo application started")
        
        // Initialize Gemma AI in background with detailed logging
        applicationScope.launch {
            val success = gemmaEngine.initialize { progress ->
                // Log progress for debugging
                Log.d("EchoApp", "Gemma AI: $progress")
            }
            
            if (success) {
                Log.d("EchoApp", "✅ Gemma AI initialized successfully!")
            } else {
                Log.w("EchoApp", "⚠️ Gemma AI initialization failed - using basic mode")
            }
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // Cancel application scope to prevent memory leaks
        applicationScope.cancel()
        Log.d("EchoApp", "Application terminated, scope cancelled")
    }
}
