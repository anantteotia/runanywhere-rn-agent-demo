package com.runanywhereagentdemo

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions
import com.runanywhere.sdk.public.extensions.generateStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

class AgentKernelModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    const val NAME = "AgentKernel"
    private const val TAG = "AgentKernel"

    const val EVENT_LOG = "AGENT_LOG"
    const val EVENT_DONE = "AGENT_DONE"
    const val EVENT_ERROR = "AGENT_ERROR"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var runJob: Job? = null

  override fun getName(): String = NAME

  private fun sendEvent(eventName: String, message: String? = null) {
    val params = Arguments.createMap()
    if (message != null) {
      params.putString("message", message)
    }
    reactContext
      .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
      .emit(eventName, params)
  }

  @ReactMethod
  fun isServiceEnabled(promise: Promise) {
    promise.resolve(AgentAccessibilityService.isEnabled(reactContext))
  }

  @ReactMethod
  fun startAgent(goal: String, promise: Promise) {
    if (!AgentAccessibilityService.isEnabled(reactContext)) {
      promise.reject("SERVICE_DISABLED", "Accessibility service is not enabled.")
      return
    }

    runJob?.cancel()
    runJob = scope.launch {
      try {
        sendEvent(EVENT_LOG, "Agent started")
        val maxSteps = 8
        for (step in 1..maxSteps) {
          sendEvent(EVENT_LOG, "Step $step/$maxSteps: scanning screen")
          val service = AgentAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility service not connected")

          val screenContext = service.getInteractiveElementsJson()
          val decisionJson = decideNextAction(goal, screenContext)
          val decision = parseDecision(decisionJson)

          val reason = decision.optString("reason", "No reason")
          sendEvent(EVENT_LOG, "Decision: ${decision.optString("action", "unknown")} - $reason")
          executeDecision(service, decision)

          if (decision.optString("action") == "done") {
            sendEvent(EVENT_DONE, "Goal achieved")
            return@launch
          }
          delay(1200)
        }
        sendEvent(EVENT_DONE, "Max steps reached")
      } catch (e: CancellationException) {
        sendEvent(EVENT_LOG, "Agent cancelled")
      } catch (e: Exception) {
        Log.e(TAG, "Agent failed: ${e.message}", e)
        sendEvent(EVENT_ERROR, e.message ?: "Agent error")
      }
    }
    promise.resolve(null)
  }

  @ReactMethod
  fun stopAgent(promise: Promise) {
    runJob?.cancel()
    runJob = null
    promise.resolve(null)
  }

  private suspend fun decideNextAction(goal: String, screenContext: String): String {
    val systemPrompt = """
You are an Android device agent. You must decide the next single action to achieve the goal.
You will receive the GOAL and a JSON list of interactive UI elements with coordinates.
Return ONLY a JSON object.

Actions:
{"action":"tap","coordinates":[x,y],"reason":"..."}
{"action":"type","text":"...","reason":"..."}
{"action":"enter","reason":"..."}
{"action":"swipe","direction":"up|down|left|right","reason":"..."}
{"action":"home","reason":"..."}
{"action":"back","reason":"..."}
{"action":"wait","reason":"..."}
{"action":"done","reason":"..."}
    """.trimIndent()

    val userPrompt = "GOAL: $goal\n\nSCREEN_CONTEXT:\n$screenContext"

    val options = LLMGenerationOptions(
      maxTokens = 256,
      temperature = 0.1f,
      topP = 0.9f,
      streamingEnabled = true,
      systemPrompt = systemPrompt
    )

    val buffer = StringBuilder()
    RunAnywhere.generateStream(userPrompt, options).collect { token ->
      buffer.append(token)
    }
    return buffer.toString()
  }

  private fun parseDecision(text: String): JSONObject {
    return try {
      JSONObject(text)
    } catch (_: JSONException) {
      val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(text)
      if (matcher.find()) {
        JSONObject(matcher.group())
      } else {
        JSONObject("""{"action":"wait","reason":"Could not parse response"}""")
      }
    }
  }

  private fun executeDecision(service: AgentAccessibilityService, decision: JSONObject) {
    when (decision.optString("action")) {
      "tap" -> {
        val coords = decision.optJSONArray("coordinates")
        if (coords != null && coords.length() == 2) {
          val x = coords.optInt(0)
          val y = coords.optInt(1)
          val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
          val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
          service.dispatchGesture(gesture, null, null)
        }
      }
      "type" -> {
        val text = decision.optString("text", "")
        val node = service.findEditableNode()
        if (node != null) {
          val args = Bundle()
          args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
          node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }
      }
      "enter" -> service.pressEnter()
      "home" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
      "back" -> service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
      "swipe" -> {
        val direction = decision.optString("direction", "up")
        val (sx, sy, ex, ey) = when (direction) {
          "down" -> listOf(540, 400, 540, 1400)
          "left" -> listOf(900, 800, 200, 800)
          "right" -> listOf(200, 800, 900, 800)
          else -> listOf(540, 1400, 540, 400)
        }
        val path = Path().apply { moveTo(sx.toFloat(), sy.toFloat()); lineTo(ex.toFloat(), ey.toFloat()) }
        val gesture = GestureDescription.Builder()
          .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
          .build()
        service.dispatchGesture(gesture, null, null)
      }
      "wait" -> {
        // no-op, delay handled by loop
      }
      "done" -> {
        // handled in loop
      }
    }
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
