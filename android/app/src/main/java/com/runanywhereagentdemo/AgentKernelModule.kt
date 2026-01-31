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
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.generate
import com.runanywhere.sdk.public.extensions.loadLLMModel
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
    private const val MODEL_ID = "qwen2.5-0.5b-instruct-q6_k"

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
        ensureModelReady()

        val maxSteps = 8
        for (step in 1..maxSteps) {
          sendEvent(EVENT_LOG, "Step $step/$maxSteps: scanning screen")
          val service = AgentAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility service not connected")

          val screenContext = service.getInteractiveElementsJson(
            maxElements = 40,
            maxTextLength = 40
          )
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

  private suspend fun ensureModelReady() {
    try {
      RunAnywhere.loadLLMModel(MODEL_ID)
      sendEvent(EVENT_LOG, "Model loaded")
    } catch (e: Exception) {
      sendEvent(EVENT_LOG, "Downloading model...")
      RunAnywhere.downloadModel(MODEL_ID).collect { progress ->
        val percent = (progress.progress * 100).toInt()
        sendEvent(EVENT_LOG, "Downloading model... $percent%")
      }
      RunAnywhere.loadLLMModel(MODEL_ID)
      sendEvent(EVENT_LOG, "Model loaded")
    }
  }

  private suspend fun decideNextAction(goal: String, screenContext: String): String {
    val systemPrompt = """
You are an Android device agent. Decide the NEXT single action to reach the goal.
You will receive: GOAL and SCREEN_CONTEXT (JSON list of interactive UI elements with coordinates).
Return ONLY a compact JSON object with one action, on a single line.
Do not use markdown or code fences.

Actions:
{"action":"tap","coordinates":[x,y],"reason":"..."}
{"action":"type","text":"...","reason":"..."}
{"action":"enter","reason":"..."}
{"action":"swipe","direction":"up|down|left|right","reason":"..."}
{"action":"home","reason":"..."}
{"action":"back","reason":"..."}
{"action":"wait","reason":"..."}
{"action":"done","reason":"..."}
If unsure, return: {"action":"wait","reason":"unsure"}
    """.trimIndent()

    val trimmedContext = shrinkContext(screenContext)
    val userPrompt = "GOAL: $goal\n\nSCREEN_CONTEXT:\n$trimmedContext"

    val options = LLMGenerationOptions(
      maxTokens = 80,
      temperature = 0.1f,
      topP = 0.9f,
      streamingEnabled = false,
      systemPrompt = systemPrompt
    )

    return try {
      val result = RunAnywhere.generate(userPrompt, options)
      result.text
    } catch (e: Exception) {
      Log.e(TAG, "Decision generation failed: ${e.message}", e)
      "{\"action\":\"wait\",\"reason\":\"LLM not ready\"}"
    }
  }

  private fun parseDecision(text: String): JSONObject {
    val cleaned = text
      .replace("```json", "")
      .replace("```", "")
      .trim()
    return try {
      JSONObject(cleaned)
    } catch (_: JSONException) {
      val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(cleaned)
      if (matcher.find()) {
        JSONObject(matcher.group())
      } else {
        sendEvent(EVENT_LOG, "LLM raw (truncated): ${cleaned.take(200)}")
        heuristicDecision(cleaned)
      }
    }
  }

  private fun heuristicDecision(text: String): JSONObject {
    val lower = text.lowercase()
    return when {
      lower.contains("home") -> JSONObject("""{"action":"home","reason":"Heuristic home"}""")
      lower.contains("back") -> JSONObject("""{"action":"back","reason":"Heuristic back"}""")
      lower.contains("done") -> JSONObject("""{"action":"done","reason":"Heuristic done"}""")
      lower.contains("wait") -> JSONObject("""{"action":"wait","reason":"Heuristic wait"}""")
      lower.contains("swipe") -> {
        val dir = when {
          lower.contains("left") -> "left"
          lower.contains("right") -> "right"
          lower.contains("down") -> "down"
          else -> "up"
        }
        JSONObject("""{"action":"swipe","direction":"$dir","reason":"Heuristic swipe"}""")
      }
      lower.contains("type") -> {
        val matcher = Pattern.compile("\"([^\"]{1,80})\"").matcher(text)
        val value = if (matcher.find()) matcher.group(1) else ""
        JSONObject("""{"action":"type","text":"$value","reason":"Heuristic type"}""")
      }
      else -> {
        val coordMatcher = Pattern.compile("(\\d{2,4})\\D+(\\d{2,4})").matcher(text)
        if (coordMatcher.find()) {
          val x = coordMatcher.group(1).toInt()
          val y = coordMatcher.group(2).toInt()
          JSONObject("""{"action":"tap","coordinates":[$x,$y],"reason":"Heuristic tap"}""")
        } else {
          JSONObject("""{"action":"wait","reason":"Could not parse response"}""")
        }
      }
    }
  }

  private fun shrinkContext(screenContext: String): String {
    val maxChars = 2500
    return if (screenContext.length <= maxChars) {
      screenContext
    } else {
      screenContext.substring(0, maxChars) + "\n...truncated..."
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
