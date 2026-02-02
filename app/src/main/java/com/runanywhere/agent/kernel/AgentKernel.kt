package com.runanywhere.agent.kernel

import android.content.Context
import android.util.Log
import com.runanywhere.agent.AgentApplication
import com.runanywhere.agent.accessibility.AgentAccessibilityService
import com.runanywhere.sdk.public.RunAnywhere
import com.runanywhere.sdk.public.extensions.LLM.LLMGenerationOptions
import com.runanywhere.sdk.public.extensions.LLM.StructuredOutputConfig
import com.runanywhere.sdk.public.extensions.downloadModel
import com.runanywhere.sdk.public.extensions.generate
import com.runanywhere.sdk.public.extensions.loadLLMModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.regex.Pattern

class AgentKernel(
    private val context: Context,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "AgentKernel"
        private const val MAX_STEPS = 15
        private const val MAX_DURATION_MS = 60_000L
        private const val STEP_DELAY_MS = 1000L
    }

    private val history = ActionHistory()
    private val screenParser = ScreenParser { AgentAccessibilityService.instance }
    private val actionExecutor = ActionExecutor(
        context = context,
        accessibilityService = { AgentAccessibilityService.instance },
        onLog = onLog
    )

    private var activeModelId: String = AgentApplication.DEFAULT_MODEL
    private var isRunning = false

    fun setModel(modelId: String) {
        activeModelId = modelId
    }

    fun getModel(): String = activeModelId

    sealed class AgentEvent {
        data class Log(val message: String) : AgentEvent()
        data class Step(val step: Int, val action: String, val result: String) : AgentEvent()
        data class Done(val message: String) : AgentEvent()
        data class Error(val message: String) : AgentEvent()
    }

    fun run(goal: String): Flow<AgentEvent> = flow {
        if (isRunning) {
            emit(AgentEvent.Error("Agent already running"))
            return@flow
        }

        isRunning = true
        history.clear()

        try {
            emit(AgentEvent.Log("Starting agent..."))

            // Check if goal matches a shortcut pattern
            if (tryShortcut(goal)) {
                emit(AgentEvent.Done("Completed via shortcut"))
                return@flow
            }

            // Ensure model is ready
            emit(AgentEvent.Log("Loading model: $activeModelId"))
            ensureModelReady()
            emit(AgentEvent.Log("Model ready"))

            val startTime = System.currentTimeMillis()
            var step = 0

            while (step < MAX_STEPS) {
                step++
                emit(AgentEvent.Log("Step $step/$MAX_STEPS"))

                // Parse screen
                val screen = screenParser.parse()
                if (screen.elementCount == 0) {
                    emit(AgentEvent.Log("No elements found, waiting..."))
                    delay(STEP_DELAY_MS)
                    continue
                }

                // Get LLM decision with context
                val historyPrompt = history.formatForPrompt()
                val lastActionResult = history.getLastActionResult()
                val lastAction = history.getLastAction()

                // Choose appropriate prompt based on context
                val prompt = when {
                    // Loop detected
                    lastAction != null && history.isRepetitive(lastAction.action, lastAction.target) -> {
                        emit(AgentEvent.Log("Loop detected, adding recovery prompt"))
                        SystemPrompts.buildLoopRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    // Recent failure
                    history.hadRecentFailure() -> {
                        emit(AgentEvent.Log("Recent failure, adding recovery hints"))
                        SystemPrompts.buildFailureRecoveryPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                    // Normal prompt with action result feedback
                    else -> {
                        SystemPrompts.buildPrompt(goal, screen.compactText, historyPrompt, lastActionResult)
                    }
                }

                val decisionJson = callLLM(prompt)
                val decision = parseDecision(decisionJson)

                emit(AgentEvent.Log("Action: ${decision.action}"))

                // Execute action
                val result = actionExecutor.execute(decision, screen.indexToCoords)
                emit(AgentEvent.Step(step, decision.action, result.message))

                // Record in history with success/failure
                val target = when {
                    decision.elementIndex != null -> screenParser.getElementLabel(decision.elementIndex)
                    decision.text != null -> decision.text
                    decision.url != null -> decision.url
                    decision.query != null -> decision.query
                    else -> null
                }
                history.record(decision.action, target, result.message, result.success)

                // Check for completion
                if (decision.action == "done") {
                    emit(AgentEvent.Done("Goal achieved"))
                    return@flow
                }

                // Check timeout
                if (System.currentTimeMillis() - startTime > MAX_DURATION_MS) {
                    emit(AgentEvent.Done("Max duration reached"))
                    return@flow
                }

                delay(STEP_DELAY_MS)
            }

            emit(AgentEvent.Done("Max steps reached"))

        } catch (e: CancellationException) {
            emit(AgentEvent.Log("Agent cancelled"))
        } catch (e: Exception) {
            Log.e(TAG, "Agent error: ${e.message}", e)
            emit(AgentEvent.Error(e.message ?: "Unknown error"))
        } finally {
            isRunning = false
        }
    }

    fun stop() {
        isRunning = false
    }

    private suspend fun ensureModelReady() {
        try {
            RunAnywhere.loadLLMModel(activeModelId)
        } catch (e: Exception) {
            onLog("Downloading model...")
            var lastPercent = -1
            RunAnywhere.downloadModel(activeModelId).collect { progress ->
                val percent = (progress.progress * 100).toInt()
                if (percent != lastPercent && percent % 10 == 0) {
                    lastPercent = percent
                    onLog("Downloading... $percent%")
                }
            }
            RunAnywhere.loadLLMModel(activeModelId)
        }
    }

    private suspend fun callLLM(prompt: String): String {
        val options = LLMGenerationOptions(
            maxTokens = 32,
            temperature = 0.0f,
            topP = 0.95f,
            streamingEnabled = false,
            systemPrompt = null,
            structuredOutput = StructuredOutputConfig(
                typeName = "Act",
                includeSchemaInPrompt = true,
                jsonSchema = SystemPrompts.DECISION_SCHEMA
            )
        )

        return try {
            val result = withContext(Dispatchers.Default) {
                RunAnywhere.generate(prompt, options)
            }
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "LLM call failed: ${e.message}", e)
            "{\"a\":\"done\"}"
        }
    }

    private fun parseDecision(text: String): Decision {
        val cleaned = text
            .replace("```json", "")
            .replace("```", "")
            .trim()

        // Try parsing as JSON
        try {
            val obj = JSONObject(cleaned)
            return extractDecision(obj)
        } catch (_: JSONException) {}

        // Try extracting JSON from text
        val matcher = Pattern.compile("\\{.*?\\}", Pattern.DOTALL).matcher(cleaned)
        if (matcher.find()) {
            try {
                return extractDecision(JSONObject(matcher.group()))
            } catch (_: JSONException) {}
        }

        // Fallback: heuristic parsing
        return heuristicDecision(cleaned)
    }

    private fun extractDecision(obj: JSONObject): Decision {
        val action = obj.optString("a", "").ifEmpty { obj.optString("action", "") }

        return Decision(
            action = action.ifEmpty { "done" },
            elementIndex = obj.optInt("i", -1).takeIf { it >= 0 },
            text = obj.optString("t", "").ifEmpty { obj.optString("text") }?.takeIf { it.isNotEmpty() },
            direction = obj.optString("d", "").ifEmpty { obj.optString("direction") }?.takeIf { it.isNotEmpty() },
            url = obj.optString("u", "").ifEmpty { obj.optString("url") }?.takeIf { it.isNotEmpty() },
            query = obj.optString("q", "").ifEmpty { obj.optString("query") }?.takeIf { it.isNotEmpty() }
        )
    }

    private fun heuristicDecision(text: String): Decision {
        val lower = text.lowercase()

        return when {
            lower.contains("done") -> Decision("done")
            lower.contains("back") -> Decision("back")
            lower.contains("home") -> Decision("home")
            lower.contains("enter") -> Decision("enter")
            lower.contains("wait") -> Decision("wait")
            lower.contains("swipe") || lower.contains("scroll") -> {
                val dir = when {
                    lower.contains("up") -> "u"
                    lower.contains("down") -> "d"
                    lower.contains("left") -> "l"
                    lower.contains("right") -> "r"
                    else -> "u"
                }
                Decision("swipe", direction = dir)
            }
            lower.contains("tap") || lower.contains("click") -> {
                val idx = Regex("\\d+").find(text)?.value?.toIntOrNull() ?: 0
                Decision("tap", elementIndex = idx)
            }
            lower.contains("type") -> {
                val textMatch = Regex("\"([^\"]+)\"").find(text)
                Decision("type", text = textMatch?.groupValues?.getOrNull(1) ?: "")
            }
            else -> Decision("done")
        }
    }

    private fun tryShortcut(goal: String): Boolean {
        val lower = goal.lowercase().trim()

        // "open <app>" shortcut
        val openPatterns = listOf("open ", "launch ", "start ")
        for (prefix in openPatterns) {
            if (lower.startsWith(prefix)) {
                var appName = goal.substring(prefix.length).trim()
                // Stop at conjunctions
                val separators = listOf(" and ", " then ", ",")
                for (sep in separators) {
                    val idx = appName.lowercase().indexOf(sep)
                    if (idx >= 0) {
                        appName = appName.substring(0, idx).trim()
                        break
                    }
                }
                // Check if it's a settings shortcut
                if (appName.lowercase().contains("setting")) {
                    return actionExecutor.openSettings()
                }
                if (appName.isNotEmpty()) {
                    return actionExecutor.openApp(appName)
                }
            }
        }

        // Settings shortcuts
        if (lower.contains("bluetooth settings") || lower == "turn on bluetooth" || lower == "turn off bluetooth") {
            return actionExecutor.openSettings("bluetooth")
        }
        if (lower.contains("wifi settings") || lower.contains("wi-fi settings")) {
            return actionExecutor.openSettings("wifi")
        }

        return false
    }
}
