package com.runanywhereagentdemo

import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule

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

    private var isRunning = false

    override fun getName(): String = NAME

    private fun sendEvent(eventName: String, params: com.facebook.react.bridge.WritableMap?) {
        reactContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    @ReactMethod
    fun initialize(apiKey: String, endpoint: String, promise: Promise) {
        Log.d(TAG, "initialize(apiKey=*****, endpoint=$endpoint)")
        // TODO: Replace with actual RunAnywhere SDK initialization
        promise.resolve(null)
    }

    @ReactMethod
    fun downloadModel(modelName: String, promise: Promise) {
        Log.d(TAG, "downloadModel(modelName=$modelName)")
        // TODO: Replace with actual SDK download call.
        // For now, simulate progress events from a background thread.
        isRunning = true
        Thread {
            try {
                for (progress in 0..100 step 10) {
                    if (!isRunning) {
                        return@Thread
                    }
                    val params = Arguments.createMap().apply {
                        putInt("progress", progress)
                    }
                    sendEvent(EVENT_DOWNLOAD_PROGRESS, params)
                    Thread.sleep(100)
                }
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("DOWNLOAD_ERROR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun loadModel(modelName: String, promise: Promise) {
        Log.d(TAG, "loadModel(modelName=$modelName)")
        // TODO: Replace with actual SDK model loading
        Thread {
            try {
                Thread.sleep(500)
                promise.resolve(null)
            } catch (e: Exception) {
                promise.reject("LOAD_ERROR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun runAgent(task: String, context: String?, promise: Promise) {
        Log.d(TAG, "runAgent(task=$task, context=$context)")
        // TODO: Replace with actual SDK agent run.
        // For now, simulate token streaming from a background thread.
        isRunning = true
        Thread {
            try {
                val tokens = listOf(
                    "Analyzing the request...\n",
                    "Setting up execution environment.\n\n",
                    "> Step 1: Parsing input parameters\n",
                    "  - Task received successfully\n",
                    "  - Validating configuration\n\n",
                    "> Step 2: Running agent logic\n",
                    "  - Connecting to model backend\n",
                    "  - Generating response tokens\n",
                    "  - Processing tool calls\n\n",
                    "> Step 3: Finalizing output\n",
                    "  - Aggregating results\n",
                    "  - Formatting response\n\n",
                    "Done. Agent completed successfully.",
                )

                for (token in tokens) {
                    if (!isRunning) {
                        return@Thread
                    }
                    val params = Arguments.createMap().apply {
                        putString("token", token)
                    }
                    sendEvent(EVENT_TOKEN, params)
                    Thread.sleep(100)
                }

                if (isRunning) {
                    sendEvent(EVENT_DONE, null)
                    isRunning = false
                }
                promise.resolve(null)
            } catch (e: Exception) {
                val params = Arguments.createMap().apply {
                    putString("message", e.message ?: "Unknown error")
                }
                sendEvent(EVENT_ERROR, params)
                isRunning = false
                promise.reject("RUN_ERROR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun cancelRun(promise: Promise) {
        Log.d(TAG, "cancelRun()")
        isRunning = false
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
