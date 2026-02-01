package com.runanywhereagentdemo

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.runanywhere.sdk.core.types.InferenceFramework
import com.runanywhere.sdk.llm.llamacpp.LlamaCPP
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.SDKEnvironment
import com.runanywhere.sdk.public.extensions.registerModel
import com.runanywhere.sdk.storage.AndroidPlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainApplication : Application(), ReactApplication {

  companion object {
    private const val TAG = "RunAnywhereApp"
  }

  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList =
        PackageList(this).packages.apply {
          add(RunAnywherePackage())
        },
    )
  }

  override fun onCreate() {
    super.onCreate()
    loadReactNative(this)

    Handler(Looper.getMainLooper()).postDelayed({
      applicationScope.launch {
        try {
          delay(200)
          initializeSDK()
        } catch (e: Exception) {
          Log.e(TAG, "SDK initialization failed: ${e.message}", e)
        }
      }
    }, 100)
  }

  private suspend fun initializeSDK() {
    Log.i(TAG, "Initializing RunAnywhere SDK...")

    AndroidPlatformContext.initialize(this@MainApplication)

    RunAnywhere.initialize(environment = SDKEnvironment.DEVELOPMENT)
    Log.i(TAG, "SDK initialized in DEVELOPMENT mode")

    RunAnywhere.completeServicesInitialization()
    Log.i(TAG, "SDK services ready")

    LlamaCPP.register(priority = 100)
    Log.i(TAG, "LlamaCPP backend registered")

    // SmolLM2 360M - Smallest, fastest, least capable
    RunAnywhere.registerModel(
      id = "smollm2-360m-instruct-q8_0",
      name = "SmolLM2 360M Instruct Q8_0",
      url = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q8_0.gguf",
      framework = InferenceFramework.LLAMA_CPP,
      memoryRequirement = 400_000_000,
    )
    Log.i(TAG, "Model registered: smollm2-360m-instruct-q8_0 (360M)")

    // Qwen2.5 1.5B - High quality, good reasoning
    RunAnywhere.registerModel(
      id = "qwen2.5-1.5b-instruct-q4_k_m",
      name = "Qwen2.5 1.5B Instruct Q4_K_M",
      url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
      framework = InferenceFramework.LLAMA_CPP,
      memoryRequirement = 1_200_000_000,
    )
    Log.i(TAG, "Model registered: qwen2.5-1.5b-instruct-q4_k_m (1.5B)")

    // LFM2.5 1.2B - Optimized for edge/mobile, fast inference
    RunAnywhere.registerModel(
      id = "lfm2.5-1.2b-instruct-q4_k_m",
      name = "LFM2.5 1.2B Instruct Q4_K_M",
      url = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
      framework = InferenceFramework.LLAMA_CPP,
      memoryRequirement = 800_000_000,
    )
    Log.i(TAG, "Model registered: lfm2.5-1.2b-instruct-q4_k_m (1.2B)")
  }
}
