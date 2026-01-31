package com.runanywhereagentdemo

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.cancelGeneration
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.generateStream
import com.runanywhere.sdk.public.extensions.loadLLMModel
import com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RunAnywhereModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    companion object {
        const val NAME = "RunAnywhere"
        const val TAG = "RunAnywhereModule"

        const val EVENT_DOWNLOAD_PROGRESS = "RUNANYWHERE_DOWNLOAD_PROGRESS"
        const val EVENT_TOKEN = "RUNANYWHERE_TOKEN"
        const val EVENT_DONE = "RUNANYWHERE_DONE"
        const val EVENT_ERROR = "RUNANYWHERE_ERROR"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null

    override fun getName(): String = NAME

    @Deprecated("Deprecated in ReactContextBaseJavaModule")
    override fun onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy()
        scope.cancel()
    }

    private fun sendEvent(eventName: String, params: com.facebook.react.bridge.WritableMap?) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun initialize(apiKey: String, endpoint: String, promise: Promise) {
        // SDK is initialized in MainApplication.onCreate().
        // This method is kept for JS-side API compatibility.
        Log.d(TAG, "initialize() — SDK already initialized via MainApplication")
        promise.resolve(null)
    }

    @ReactMethod
    fun downloadModel(modelId: String, promise: Promise) {
        Log.d(TAG, "downloadModel(modelId=$modelId)")
        scope.launch {
            try {
                RunAnywhere.downloadModel(modelId).collect { progress ->
                    val progressPercent = (progress.progress * 100).toInt()
                    val params = Arguments.createMap().apply {
                        putInt("progress", progressPercent)
                    }
                    sendEvent(EVENT_DOWNLOAD_PROGRESS, params)
                }
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                val params = Arguments.createMap().apply {
                    putString("message", e.message ?: "Download failed")
                }
                sendEvent(EVENT_ERROR, params)
                promise.reject("DOWNLOAD_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun loadModel(modelId: String, promise: Promise) {
        Log.d(TAG, "loadModel(modelId=$modelId)")
        scope.launch {
            try {
                RunAnywhere.loadLLMModel(modelId)
                Log.i(TAG, "Model loaded: $modelId")
                promise.resolve(null)
            } catch (e: Exception) {
                Log.e(TAG, "Load failed: ${e.message}", e)
                val params = Arguments.createMap().apply {
                    putString("message", e.message ?: "Model load failed")
                }
                sendEvent(EVENT_ERROR, params)
                promise.reject("LOAD_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun runAgent(task: String, context: String?, promise: Promise) {
        Log.i(TAG, "runAgent(task=$task)")
        runJob?.cancel()

        val prompt = if (context.isNullOrBlank()) {
            task
        } else {
            "$task\n\nContext: $context"
        }

        val options = LLMGenerationOptions(
            maxTokens = 512,
            temperature = 0.2f,
            topP = 0.9f,
            stopSequences = listOf("\n\n", "\nUser:", "\nTask:", "###"),
            streamingEnabled = true,
            systemPrompt = "You are a concise assistant. Answer the user's question in one sentence.",
        )

        runJob = scope.launch {
            try {
                RunAnywhere.generateStream(prompt, options).collect { token ->
                    val params = Arguments.createMap().apply {
                        putString("token", token)
                    }
                    sendEvent(EVENT_TOKEN, params)
                }
                sendEvent(EVENT_DONE, null)
                promise.resolve(null)
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Generation cancelled")
                // Don't reject — cancellation is expected via cancelRun
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed: ${e.message}", e)
                val params = Arguments.createMap().apply {
                    putString("message", e.message ?: "Generation failed")
                }
                sendEvent(EVENT_ERROR, params)
                promise.reject("RUN_ERROR", e.message, e)
            }
        }
    }

    @ReactMethod
    fun cancelRun(promise: Promise) {
        Log.d(TAG, "cancelRun()")
        runJob?.cancel()
        runJob = null
        RunAnywhere.cancelGeneration()
        promise.resolve(null)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for NativeEventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for NativeEventEmitter
    }
}
