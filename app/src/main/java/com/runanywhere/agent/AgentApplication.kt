package com.runanywhere.agent

import android.app.Application
import android.util.Log
import com.runanywhere.sdk.public.AndroidPlatformContext
import com.runanywhere.sdk.public.ModelRegistration
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.SDKEnvironment
import com.runanywhere.sdk.public.extensions.LLM.LlamaCPP
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AgentApplication : Application() {

    companion object {
        private const val TAG = "AgentApplication"

        // Available models
        val AVAILABLE_MODELS = listOf(
            ModelInfo(
                id = "smollm2-360m-instruct-q8_0",
                name = "SmolLM2 360M (Fast)",
                url = "https://huggingface.co/runanywhere/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
                sizeBytes = 400_000_000L
            ),
            ModelInfo(
                id = "qwen2.5-1.5b-instruct-q4_k_m",
                name = "Qwen2.5 1.5B (Best)",
                url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
                sizeBytes = 1_200_000_000L
            ),
            ModelInfo(
                id = "lfm2.5-1.2b-instruct-q4_k_m",
                name = "LFM2.5 1.2B (Edge)",
                url = "https://huggingface.co/runanywhere/LFM2.5-1.2B-Instruct-GGUF/resolve/main/lfm2.5-1.2b-instruct-q4_k_m.gguf",
                sizeBytes = 800_000_000L
            )
        )

        const val DEFAULT_MODEL = "qwen2.5-1.5b-instruct-q4_k_m"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initializeSDK()
    }

    private fun initializeSDK() {
        scope.launch {
            try {
                delay(100) // Allow app to initialize

                Log.i(TAG, "Initializing RunAnywhere SDK...")
                AndroidPlatformContext.initialize(applicationContext)
                RunAnywhere.initialize(SDKEnvironment.DEVELOPMENT)
                RunAnywhere.completeServicesInitialization()

                // Register LlamaCPP backend
                LlamaCPP.register(priority = 100)

                // Register available models
                AVAILABLE_MODELS.forEach { model ->
                    RunAnywhere.registerModel(
                        ModelRegistration(
                            id = model.id,
                            url = model.url,
                            requiredMemoryBytes = model.sizeBytes
                        )
                    )
                    Log.i(TAG, "Registered model: ${model.id}")
                }

                Log.i(TAG, "RunAnywhere SDK initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize SDK: ${e.message}", e)
            }
        }
    }
}

data class ModelInfo(
    val id: String,
    val name: String,
    val url: String,
    val sizeBytes: Long
)
