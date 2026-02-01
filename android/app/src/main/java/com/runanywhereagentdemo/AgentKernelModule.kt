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
import com.runanywhere.sdk.public.extensions.LLM.StructuredOutputConfig
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
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

class AgentKernelModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    const val NAME = "AgentKernel"
    private const val TAG = "AgentKernel"
    private const val MODEL_ID = "smollm2-360m-instruct-q8_0"

    const val EVENT_LOG = "AGENT_LOG"
    const val EVENT_DONE = "AGENT_DONE"
    const val EVENT_ERROR = "AGENT_ERROR"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var runJob: Job? = null
  private var lastAppExamples: String? = null
  private var currentElementMap: Map<Int, Pair<Int, Int>> = emptyMap()

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

        val appName = extractAppName(goal)
        if (appName != null) {
          val opened = openAppByName(appName)
          if (opened) {
            sendEvent(EVENT_DONE, "Opened app: $appName")
            return@launch
          }
          val examples = lastAppExamples
          val message = if (!examples.isNullOrBlank()) {
            "App not found: $appName. Try: $examples"
          } else {
            "App not found: $appName"
          }
          sendEvent(EVENT_ERROR, message)
          return@launch
        }

        if (shouldOpenSettings(goal)) {
          val opened = openRelevantSettings(goal)
          if (opened) {
            delay(1000)
            val toggled = tryToggleSetting(goal)
            sendEvent(
              EVENT_DONE,
              if (toggled) "Toggled setting" else "Opened settings"
            )
            return@launch
          }
        }

        val maxSteps = 10
        val maxDurationMs = 45_000L
        val startTime = System.currentTimeMillis()
        var step = 0
        while (step < maxSteps) {
          step += 1
          sendEvent(EVENT_LOG, "Step $step")
          val service = AgentAccessibilityService.instance
            ?: throw IllegalStateException("Accessibility service not connected")

          // Get compact screen state with element index map
          val (screenState, elementMap) = service.getCompactScreenState(
            maxElements = 8,
            maxTextLength = 16
          )
          currentElementMap = elementMap

          val decisionJson = decideNextAction(goal, screenState)
          val decision = parseDecision(decisionJson, currentElementMap)

          sendEvent(EVENT_LOG, "Action: ${decision.optString("action", "unknown")}")
          executeDecision(service, decision)

          if (decision.optString("action") == "done") {
            sendEvent(EVENT_DONE, "Goal achieved")
            return@launch
          }
          delay(1000)
          if (System.currentTimeMillis() - startTime > maxDurationMs) {
            sendEvent(EVENT_DONE, "Max duration reached")
            return@launch
          }
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
      var lastPercent = -1
      RunAnywhere.downloadModel(MODEL_ID).collect { progress ->
        val percent = (progress.progress * 100).toInt()
        if (percent != lastPercent && percent % 5 == 0) {
          lastPercent = percent
          sendEvent(EVENT_LOG, "Downloading model... $percent%")
        }
      }
      RunAnywhere.loadLLMModel(MODEL_ID)
      sendEvent(EVENT_LOG, "Model loaded")
    }
  }

  private suspend fun decideNextAction(goal: String, screenState: String): String {
    // Compact prompt optimized for small LLMs (~80 tokens)
    val userPrompt = """
GOAL:$goal
SCREEN:
$screenState
ACT:tap,type,swipe,back,home,done
OUT:{"a":"ACTION","i":INDEX,"t":"text","d":"u/d/l/r"}
    """.trimIndent()

    // Minimal schema with short field names
    val schema = """
{
  "type":"object",
  "properties":{
    "a":{"type":"string","enum":["tap","type","swipe","back","home","done"]},
    "i":{"type":"integer"},
    "t":{"type":"string"},
    "d":{"type":"string","enum":["u","d","l","r"]}
  },
  "required":["a"]
}
    """.trimIndent()

    val options = LLMGenerationOptions(
      maxTokens = 24,
      temperature = 0.0f,
      topP = 0.95f,
      streamingEnabled = false,
      systemPrompt = null,
      structuredOutput = StructuredOutputConfig(
        typeName = "Act",
        includeSchemaInPrompt = true,
        jsonSchema = schema
      )
    )

    return try {
      val result = withContext(Dispatchers.Default) {
        RunAnywhere.generate(userPrompt, options)
      }
      result.text
    } catch (e: Exception) {
      Log.e(TAG, "Decision generation failed: ${e.message}", e)
      "{\"a\":\"done\"}"
    }
  }

  private fun parseDecision(text: String, elementMap: Map<Int, Pair<Int, Int>>): JSONObject {
    val cleaned = text
      .replace("```json", "")
      .replace("```", "")
      .trim()

    // Try parsing as compact JSON format first
    try {
      val obj = JSONObject(cleaned)
      return expandCompactDecision(obj, elementMap)
    } catch (_: JSONException) {}

    // Try extracting JSON from text
    val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(cleaned)
    if (matcher.find()) {
      try {
        return expandCompactDecision(JSONObject(matcher.group()), elementMap)
      } catch (_: JSONException) {}
    }

    // Fallback to heuristic parsing
    sendEvent(EVENT_LOG, "Heuristic: ${cleaned.take(60)}")
    return heuristicDecision(cleaned, elementMap)
  }

  private fun expandCompactDecision(obj: JSONObject, elementMap: Map<Int, Pair<Int, Int>>): JSONObject {
    // Handle both compact ("a") and legacy ("action") field names
    val action = obj.optString("a", "").ifEmpty { obj.optString("action", "") }
    val result = JSONObject()

    when (action) {
      "tap" -> {
        val idx = obj.optInt("i", -1)
        val coords = elementMap[idx]
        if (coords != null) {
          result.put("action", "tap")
          result.put("coordinates", org.json.JSONArray().put(coords.first).put(coords.second))
        } else {
          // Fallback: check for legacy coordinates
          val legacyCoords = obj.optJSONArray("coordinates")
          if (legacyCoords != null && legacyCoords.length() == 2) {
            result.put("action", "tap")
            result.put("coordinates", legacyCoords)
          } else {
            result.put("action", "done")
          }
        }
      }
      "type" -> {
        result.put("action", "type")
        result.put("text", obj.optString("t", "").ifEmpty { obj.optString("text", "") })
      }
      "swipe" -> {
        result.put("action", "swipe")
        val d = obj.optString("d", "").ifEmpty { obj.optString("direction", "up") }
        result.put("direction", when (d) {
          "u", "up" -> "up"
          "d", "down" -> "down"
          "l", "left" -> "left"
          "r", "right" -> "right"
          else -> "up"
        })
      }
      "back" -> result.put("action", "back")
      "home" -> result.put("action", "home")
      "done" -> result.put("action", "done")
      "wait" -> result.put("action", "wait")
      else -> result.put("action", "done")
    }
    return result
  }

  private fun heuristicDecision(text: String, elementMap: Map<Int, Pair<Int, Int>>): JSONObject {
    val lower = text.lowercase()

    // Look for tap with index
    if (lower.contains("tap")) {
      val idxMatch = Regex("\\d+").find(text)
      val idx = idxMatch?.value?.toIntOrNull()
      if (idx != null && elementMap.containsKey(idx)) {
        val coords = elementMap[idx]!!
        return JSONObject().apply {
          put("action", "tap")
          put("coordinates", org.json.JSONArray().put(coords.first).put(coords.second))
        }
      }
    }

    // Look for type with quoted text
    if (lower.contains("type")) {
      val match = Regex("\"([^\"]+)\"").find(text)
      val value = match?.groupValues?.getOrNull(1) ?: ""
      return JSONObject().apply {
        put("action", "type")
        put("text", value)
      }
    }

    // Simple action keywords
    return when {
      lower.contains("done") -> JSONObject().put("action", "done")
      lower.contains("back") -> JSONObject().put("action", "back")
      lower.contains("home") -> JSONObject().put("action", "home")
      lower.contains("swipe") -> {
        val dir = when {
          lower.contains("up") || lower.contains(" u ") -> "up"
          lower.contains("down") || lower.contains(" d ") -> "down"
          lower.contains("left") || lower.contains(" l ") -> "left"
          else -> "right"
        }
        JSONObject().put("action", "swipe").put("direction", dir)
      }
      else -> {
        // Last resort: look for any index number and tap it
        val anyIdx = Regex("\\d+").find(text)?.value?.toIntOrNull()
        if (anyIdx != null && elementMap.containsKey(anyIdx)) {
          val coords = elementMap[anyIdx]!!
          JSONObject().apply {
            put("action", "tap")
            put("coordinates", org.json.JSONArray().put(coords.first).put(coords.second))
          }
        } else {
          JSONObject().put("action", "done")
        }
      }
    }
  }

  private fun shouldOpenSettings(goal: String): Boolean {
    val lower = goal.lowercase()
    return lower.contains("settings") ||
      lower.contains("bluetooth") ||
      lower.contains("wi-fi") ||
      lower.contains("wifi")
  }

  private fun extractAppName(goal: String): String? {
    val lower = goal.lowercase().trim()
    val patterns = listOf("open ", "launch ", "start ")
    for (prefix in patterns) {
      if (lower.startsWith(prefix)) {
        val name = goal.substring(prefix.length).trim()
        if (name.isNotEmpty()) return name
      }
    }
    return null
  }

  private fun openAppByName(appName: String): Boolean {
    return try {
      val pm = reactContext.packageManager
      val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
        addCategory(android.content.Intent.CATEGORY_LAUNCHER)
      }
      val apps = pm.queryIntentActivities(intent, 0)
      val target = normalizeAppToken(appName)
      if (target.isEmpty()) {
        sendEvent(EVENT_LOG, "Shortcut: app name empty")
        return false
      }
      val match = apps.firstOrNull { info ->
        val label = info.loadLabel(pm)?.toString().orEmpty()
        val labelNorm = normalizeAppToken(label)
        val pkgNorm = normalizeAppToken(info.activityInfo.packageName ?: "")
        labelNorm.contains(target) || pkgNorm.contains(target)
      }
      if (match != null) {
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        launch?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) {
          reactContext.startActivity(launch)
          sendEvent(EVENT_LOG, "Shortcut: opening app \"$appName\"")
          true
        } else {
          sendEvent(EVENT_LOG, "Shortcut: no launch intent for \"$appName\"")
          false
        }
      } else {
        val examples = apps
          .mapNotNull { info -> info.loadLabel(pm)?.toString() }
          .distinct()
          .take(5)
          .joinToString(", ")
        lastAppExamples = examples
        sendEvent(EVENT_LOG, "Shortcut: app not found \"$appName\". Examples: $examples")
        false
      }
    } catch (e: Exception) {
      sendEvent(EVENT_LOG, "Failed to open app: ${e.message}")
      false
    }
  }

  private fun normalizeAppToken(value: String): String {
    return value.lowercase().replace("[^a-z0-9]".toRegex(), "")
  }

  private fun openRelevantSettings(goal: String): Boolean {
    val lower = goal.lowercase()
    return when {
      lower.contains("bluetooth") -> {
        sendEvent(EVENT_LOG, "Shortcut: opening Bluetooth settings")
        openSettingsIntent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
      }
      lower.contains("wi-fi") || lower.contains("wifi") -> {
        sendEvent(EVENT_LOG, "Shortcut: opening Wi-Fi settings")
        openSettingsIntent(android.provider.Settings.ACTION_WIFI_SETTINGS)
      }
      else -> {
        sendEvent(EVENT_LOG, "Shortcut: opening Settings")
        openSettingsIntent(android.provider.Settings.ACTION_SETTINGS)
      }
    }
  }

  private fun openSettingsIntent(action: String): Boolean {
    try {
      val intent = android.content.Intent(action).apply {
        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      reactContext.startActivity(intent)
      return true
    } catch (e: Exception) {
      sendEvent(EVENT_LOG, "Failed to open Settings: ${e.message}")
      return false
    }
  }

  private fun tryToggleSetting(goal: String): Boolean {
    val service = AgentAccessibilityService.instance ?: return false
    val lower = goal.lowercase()
    val keyword = if (lower.contains("bluetooth")) "bluetooth" else if (lower.contains("wi-fi") || lower.contains("wifi")) "wi-fi" else "settings"
    val node = service.findToggleNode(keyword) ?: return false
    val handled = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
    sendEvent(EVENT_LOG, "Tried toggle for $keyword: ${if (handled) "clicked" else "not clickable"}")
    return handled
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
